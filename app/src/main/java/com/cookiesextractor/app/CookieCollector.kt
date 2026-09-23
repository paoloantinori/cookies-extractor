package com.cookiesextractor.app

/**
 * Multi-domain cookie collection with structured JSON serialization. Pure JVM:
 * no Android imports, runs under plain JUnit like RedirectCapture and DebugHttp.
 */
object CookieCollector {

    data class Cookie(val name: String, val value: String, val domain: String)

    val EXTRA_URLS = listOf(
        "https://accounts.google.com/",
        "https://www.google.com/",
        "https://www.google.it/",
    )

    fun parseCookieHeader(header: String, domain: String): List<Cookie> {
        if (header.isBlank()) return emptyList()
        return header.split(";").mapNotNull { pair ->
            val trimmed = pair.trim()
            val eq = trimmed.indexOf('=')
            if (eq < 1) return@mapNotNull null
            val name = trimmed.substring(0, eq).trim()
            // a name is a token: internal whitespace means a malformed pair, not a cookie
            if (name.isEmpty() || name.any { it.isWhitespace() }) return@mapNotNull null
            Cookie(
                name = name,
                value = trimmed.substring(eq + 1).trim(),
                domain = domain,
            )
        }
    }

    /**
     * Registrable domain (eTLD+1) from a URL, dot-prefixed. Two-label heuristic:
     * sufficient for the constant Google-domain list; compound TLDs (.co.uk) would
     * need a Public Suffix List lookup.
     */
    fun registrableDomain(url: String): String {
        val host = hostFromUrl(url) ?: return ""
        val parts = host.split(".")
        if (parts.size < 2 || parts.any { it.isEmpty() }) return ""
        return ".${parts.takeLast(2).joinToString(".")}"
    }

    private fun hostFromUrl(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        // drop userinfo (user:pass@host) before the port split, or it leaks into the host
        return afterScheme.substringBefore("/")
            .substringAfterLast("@")
            .substringBefore(":")
            .lowercase()
    }

    fun collect(sources: List<Pair<String, String?>>): List<Cookie> {
        val seen = mutableSetOf<Pair<String, String>>()
        val cookies = mutableListOf<Cookie>()
        for ((url, header) in sources) {
            if (header.isNullOrBlank()) continue
            val domain = registrableDomain(url)
            if (domain.isEmpty()) continue
            for (cookie in parseCookieHeader(header, domain)) {
                val key = cookie.name to cookie.domain
                if (seen.add(key)) cookies.add(cookie)
            }
        }
        return cookies
    }

    fun toJson(cookies: List<Cookie>): String {
        val sb = StringBuilder()
        sb.append("{\"version\":2,\"cookies\":[")
        cookies.forEachIndexed { i, c ->
            if (i > 0) sb.append(",")
            sb.append("{\"name\":").append(Api.json(c.name))
            sb.append(",\"value\":").append(Api.json(c.value))
            sb.append(",\"domain\":").append(Api.json(c.domain))
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    fun collectAndSerialize(sources: List<Pair<String, String?>>): String =
        toJson(collect(sources))
}
