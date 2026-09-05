package com.cookiesextractor.app

/**
 * Pure-JVM pieces of the debug channel (COK-10): HTTP request-head parsing, query-string
 * decoding, and JSON string escaping. No android imports so all of it runs under plain
 * JUnit, where android.jar is stubbed. Every function is total: bad input yields null or a
 * verbatim value, never a throw.
 */
object DebugHttp {

    /** A parsed request head; the channel only accepts GET, so there is no method field. */
    data class Request(
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
    )

    /**
     * Parses a request head: everything up to (and including) the first blank line. Null
     * unless the text starts with an "GET <target> HTTP/1.x" request line. Header names are
     * lowercased; duplicate query keys keep the first value, matching
     * android.net.Uri.getQueryParameter.
     */
    fun parse(head: String): Request? {
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size != 3) return null
        if (requestLine[0] != "GET") return null
        if (!requestLine[2].startsWith("HTTP/1.")) return null
        val target = requestLine[1]
        val queryStart = target.indexOf('?')
        val path = if (queryStart >= 0) target.substring(0, queryStart) else target
        val query =
            if (queryStart >= 0) parseQuery(target.substring(queryStart + 1)) else emptyMap()
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return Request(path, query, headers)
    }

    /**
     * Splits an URL-encoded query string on '&' and '='. Names and values use the same
     * decoding as OAuth parameters ('+' is a space, strict '%xx'), via the tested decoder in
     * [RedirectCapture]; a component that fails to decode stays verbatim, and so does a
     * segment without '='.
     */
    fun parseQuery(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (segment in raw.split('&')) {
            if (segment.isEmpty()) continue
            val eq = segment.indexOf('=')
            if (eq < 0) out.putIfAbsent(decode(segment), "")
            else out.putIfAbsent(
                decode(segment.substring(0, eq)),
                decode(segment.substring(eq + 1)),
            )
        }
        return out
    }

    /**
     * Decodes a JSON string literal, quotes included: WebView.evaluateJavascript returns its
     * result in exactly this shape. Null when [s] is not a well-formed literal.
     */
    fun jsonUnescape(s: String): String? {
        if (s.length < 2 || !s.startsWith("\"") || !s.endsWith("\"")) return null
        val body = s.substring(1, s.length - 1)
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 >= body.length) return null
            when (val esc = body[i + 1]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('')
                'u' -> {
                    if (i + 5 >= body.length) return null
                    val hex = body.substring(i + 2, i + 6)
                    val code = hex.toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    i += 6
                    continue
                }
                else -> return null
            }
            i += 2
        }
        return out.toString()
    }

    private fun decode(s: String): String =
        runCatching { RedirectCapture.percentDecode(s) }.getOrDefault(s)
}
