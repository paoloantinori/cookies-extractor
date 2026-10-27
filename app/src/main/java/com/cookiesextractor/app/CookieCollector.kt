package com.cookiesextractor.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Multi-domain cookie collection with structured JSON serialization. Pure JVM:
 * no Android imports, runs under plain JUnit like RedirectCapture and DebugHttp
 * (org.json resolves to the platform classes at runtime and to the TEST-scoped
 * shadow artifact under unit tests, same as MonitorAlerts).
 */
object CookieCollector {

    data class Cookie(val name: String, val value: String, val domain: String)

    val DEFAULT_EXTRA_URLS = listOf(
        "https://accounts.google.com/",
        "https://www.google.com/",
        "https://www.google.it/",
    )

    /** DNS-name/IPv6-literal host alphabet (brackets already stripped, dots and IPv6 colons kept). */
    private val HOST_CHARS = Regex("[a-z0-9.:\\-]+")

    /**
     * Two-label host-family key (the split('.').takeLast(2) approximation): the single
     * shared definition for cookie grouping and capture-release comparison. Cookie
     * grouping refines it per registrable domain (see [registrableDomain]); this bare
     * form exists so both call sites can never drift again. Compound-TLD hosts over-
     * group here by design (errs toward keeping a capture; COK-38 hoist).
     */
    fun hostFamilyKey(host: String): String? {
        val labels = host.split(".")
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        return labels.takeLast(2).joinToString(".")
    }

    /**
     * Parses a user-supplied extra-URL list: one entry per line, '#' comments and blank
     * lines skipped, scheme-less entries normalized to https, invalid ones dropped
     * silently (the user list is edited by hand in a dialog; typos must not break the
     * share). Only http(s) entries survive; anything else can never contribute cookies.
     * Pure JVM for unit tests.
     */
    fun parseExtraUrls(raw: String): List<String> =
        raw.lines().mapNotNull { line ->
            val entry = line.substringBefore('#').trim()
            if (entry.isEmpty()) return@mapNotNull null
            // a real scheme token has no dot and no slash; "hosthttps://x" (an IME-joined
            // line) must be rejected, not stored verbatim as a never-matching URL, while
            // a legitimate path or query before an embedded https still parses fine
            // shared bare-host-gets-https policy (COK-39); only http(s) survives the
            // allowlist because other schemes can never contribute cookies
            val withScheme = Api.normalizeUserUrl(entry)
            val schemePart = withScheme.substringBefore("://", missingDelimiterValue = "")
            if (schemePart != "http" && schemePart != "https") return@mapNotNull null
            val host = hostFromUrl(withScheme) ?: return@mapNotNull null
            // dotless intranet names and bracketed IPv6 are valid extras (domainLabel
            // labels them with the full host); anything with whitespace or characters
            // outside the host alphabet can never match a getCookie query
            if (host.isEmpty() || !HOST_CHARS.matches(host)) null else withScheme
        }.distinct()

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
     * Public suffixes with more than one label that matter for logins (curated, not the
     * full PSL: 52 entries cover the compound ccTLD families a real IdP sits under).
     * Matched against the host tail, longest first, so ".ac.uk" wins over a hypothetical
     * ".uk". Pure data, no dependency.
     */
    private val COMPOUND_SUFFIXES = listOf(
        "ac.uk", "co.uk", "gov.uk", "org.uk", "me.uk", "net.uk",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
        "com.au", "net.au", "org.au", "co.nz", "net.nz", "org.nz",
        "com.br", "net.br", "org.br", "com.mx", "com.ar", "com.tr",
        "co.in", "net.in", "org.in", "co.za", "com.cn", "net.cn",
        "com.tw", "com.hk", "com.sg", "co.kr", "com.ua",
        "co.il", "org.il", "co.th", "or.th", "com.pl", "com.ph",
        "com.vn", "com.my", "com.co", "com.pe", "com.eg", "com.pk",
        "com.bd", "co.id", "or.id", "com.sa", "com.ng", "com.gh",
    ).distinct()

    /**
     * Registrable domain (eTLD+1) from a URL, dot-prefixed. Compound suffixes from the
     * curated table keep their private label (gitlab.mycompany.co.uk -> .mycompany.co.uk,
     * not .co.uk); everything else uses the two-label rule. IP-address hosts have no
     * registrable domain and return "" (the caller falls back to the full-host label).
     */
    fun registrableDomain(url: String): String {
        val host = hostFromUrl(url) ?: return ""
        if (isIpAddress(host)) return ""
        val suffix = COMPOUND_SUFFIXES
            .filter { host.endsWith(".$it") }
            .maxByOrNull { it.length }
        if (suffix != null) {
            val labels = host.split(".")
            val keep = suffix.count { it == '.' } + 2
            return ".${labels.takeLast(keep).joinToString(".")}"
        }
        return hostFamilyKey(host)?.let { ".$it" } ?: ""
    }

    /**
     * Dotted-quad IPv4 (all-numeric labels). Any host containing ':' is an IPv6 literal
     * (hostFromUrl strips the brackets), including IPv4-mapped forms that carry dots.
     */
    private fun isIpAddress(host: String): Boolean {
        if (host.contains(":")) return true
        if (!host.contains(".")) return false
        val labels = host.split(".")
        return labels.size == 4 && labels.all { it.isNotEmpty() && it.all { c -> c.isDigit() } }
    }

    internal fun hostFromUrl(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        // the authority ends at /, ?, or # (a query-only URL is not part of the host)
        val authority = afterScheme.substringBefore("/").substringBefore("?").substringBefore("#")
        // drop userinfo (user:pass@host) before the port split, or it leaks into the host
        val noUserinfo = authority.substringAfterLast("@")
        // IPv6 literal: [2001:db8::1]:8443; the bracket content is the host, as-is
        if (noUserinfo.startsWith("[")) {
            return noUserinfo.substringBefore("]").removePrefix("[").lowercase().ifEmpty { null }
        }
        return noUserinfo.substringBefore(":").lowercase()
    }

    /**
     * Domain label for a collected source: the registrable domain when there is one,
     * otherwise the full host (dotless intranet names and IP literals have no eTLD+1;
     * skipping them would silently empty the structured share where flat mode worked).
     * IPv6 hosts keep their brackets so the label cannot collide with a DNS name.
     */
    private fun domainLabel(url: String): String? {
        val registrable = registrableDomain(url)
        if (registrable.isNotEmpty()) return registrable
        val host = hostFromUrl(url) ?: return null
        if (host.isEmpty()) return null
        return if (host.contains(":")) "[$host]" else host
    }

    fun collect(sources: List<Pair<String, String?>>): List<Cookie> {
        val seen = mutableSetOf<Triple<String, String, String>>()
        val cookies = mutableListOf<Cookie>()
        for ((url, header) in sources) {
            if (header.isNullOrBlank()) continue
            val domain = domainLabel(url) ?: continue
            for (cookie in parseCookieHeader(header, domain)) {
                // identity includes the value: the same cookie read from two hosts of
                // one family still merges, but distinct same-named values (host-only
                // SESSION per subdomain) all ship instead of first-wins
                val key = Triple(cookie.name, cookie.domain, cookie.value)
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

    /**
     * Validates and canonicalizes the raw token entries [Api.SLACK_TOKENS_SCRIPT]
     * returns: entries survive only with a non-blank team_id and an xoxc- prefixed
     * token; enterprise_id and team_name emit as JSON null when absent. Null when the
     * input is null, unparsable, or empty after filtering.
     */
    internal fun filteredSlackTokens(tokensJson: String?): JSONArray? {
        if (tokensJson == null) return null
        val entries = runCatching { JSONArray(tokensJson) }.getOrNull() ?: return null
        val tokens = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val teamId = entry.optString("team_id").trim()
            val xoxc = entry.optString("xoxc").trim()
            if (teamId.isEmpty() || !xoxc.startsWith("xoxc-")) continue
            val enterpriseId = entry.optString("enterprise_id").trim()
            val teamName = entry.optString("team_name").trim()
            tokens.put(
                JSONObject()
                    .put("team_id", teamId)
                    .put("enterprise_id", if (enterpriseId.isEmpty()) JSONObject.NULL else enterpriseId)
                    .put("team_name", if (teamName.isEmpty()) JSONObject.NULL else teamName)
                    .put("xoxc", xoxc)
            )
        }
        return if (tokens.length() == 0) null else tokens
    }

    /**
     * Splices the gateway contract's optional slack field (COK-35) into a [toJson]
     * envelope: ,"slack":{"tokens":[{team_id,enterprise_id,team_name,xoxc}...]}. Null
     * when [filteredSlackTokens] yields nothing: the caller then shares the envelope
     * unchanged, so a malformed read can never corrupt the share.
     */
    fun withSlackTokens(envelope: String, tokensJson: String?): String? {
        val tokens = filteredSlackTokens(tokensJson) ?: return null
        // splice before the root brace; any envelope shape change degrades to "no slack
        // field" instead of corrupt JSON
        if (!envelope.endsWith("]}")) return null
        return envelope.dropLast(1) + ",\"slack\":{\"tokens\":" + tokens + "}}"
    }

    /** Entry count of the filtered slack tokens (0 when none); shares the single filter. */
    fun slackTokenCount(tokensJson: String?): Int = filteredSlackTokens(tokensJson)?.length() ?: 0
}
