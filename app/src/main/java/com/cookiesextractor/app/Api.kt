package com.cookiesextractor.app

/**
 * Pure-JVM routing and contracts for the versioned agent SPI (COK-24), served by the
 * COK-10 debug channel under the /api/v1 prefix. One server, one auth: every request carries the
 * channel token exactly like the legacy endpoints, and the SPI inherits the channel's
 * off-by-default stance. No android imports so all of it runs under plain JUnit.
 *
 * [parse] is the single router (path + query in, sealed [Command] out); MainActivity
 * executes commands and renders [Ok]/[Err] via [json] / [errorJson]. Errors follow one
 * model everywhere: {"error":{"code":"...","message":"..."}}.
 *
 * Deliberately GET-only, mutations included: the channel parser accepts GETs only, and an
 * agent driving this app is automation, not a browser form.
 */
object Api {

    const val API_LEVEL = 1
    const val PREFIX = "/api/v1"

    /** Route names advertised by /api/v1/info; agents feature-detect on this list. */
    val CAPABILITIES: List<String> = listOf(
        "info", "status", "navigate", "text", "cookies", "capture", "clear",
        "bookmarks", "bookmarks.add", "bookmarks.delete", "template", "template.set",
        "console", "fill", "submit",
    )

    sealed interface Command {
        data object Info : Command
        data object Status : Command
        data class Navigate(val url: String, val waitForLoad: Boolean) : Command
        data object Text : Command
        data object Cookies : Command
        data object Capture : Command
        data object Clear : Command
        data object Bookmarks : Command
        data class BookmarkAdd(val url: String, val title: String?) : Command
        data class BookmarkDelete(val url: String) : Command
        data object Template : Command
        data class TemplateSet(val template: String) : Command
        data class Console(val lines: Int) : Command
        data class Fill(val selector: String, val value: String) : Command
        data class Submit(val selector: String?) : Command
    }

    /** Router output: a parsed command or a terminal error (with its HTTP status). */
    sealed interface Parse {
        data class Ok(val command: Command) : Parse
        data class Err(val status: Int, val code: String, val message: String) : Parse
    }

    /** Executes-elsewhere result contract; MainActivity turns these into HTTP responses. */
    sealed interface Outcome {
        data class Ok(val status: Int, val json: String) : Outcome
        data class Err(val status: Int, val code: String, val message: String) : Outcome
    }

    fun parse(path: String, query: Map<String, String>): Parse {
        if (!path.startsWith("$PREFIX/")) return unknown(path)
        return when (path.removePrefix(PREFIX)) {
            "/info" -> Parse.Ok(Command.Info)
            "/status" -> Parse.Ok(Command.Status)
            "/navigate" -> {
                val url = query["url"].orEmpty()
                if (url.isEmpty()) return Parse.Err(400, "missing_url", "url parameter is required")
                Parse.Ok(Command.Navigate(url, query["wait"] == "load"))
            }
            "/text" -> Parse.Ok(Command.Text)
            "/cookies" -> Parse.Ok(Command.Cookies)
            "/capture" -> Parse.Ok(Command.Capture)
            "/clear" -> Parse.Ok(Command.Clear)
            "/bookmarks" -> Parse.Ok(Command.Bookmarks)
            "/bookmarks/add" -> {
                val url = query["url"].orEmpty()
                if (url.isEmpty()) return Parse.Err(400, "missing_url", "url parameter is required")
                Parse.Ok(Command.BookmarkAdd(url, query["title"]?.takeIf { it.isNotBlank() }))
            }
            "/bookmarks/delete" -> {
                val url = query["url"].orEmpty()
                if (url.isEmpty()) return Parse.Err(400, "missing_url", "url parameter is required")
                Parse.Ok(Command.BookmarkDelete(url))
            }
            "/template" -> Parse.Ok(Command.Template)
            "/template/set" -> Parse.Ok(Command.TemplateSet(query["t"].orEmpty()))
            "/console" -> Parse.Ok(
                Command.Console(query["n"]?.toIntOrNull()?.coerceIn(1, DebugChannelServer.MAX_CONSOLE_LINES) ?: 50)
            )
            "/fill" -> {
                val selector = query["selector"].orEmpty()
                if (selector.isEmpty()) return Parse.Err(400, "missing_selector", "selector parameter is required")
                Parse.Ok(Command.Fill(selector, query["value"].orEmpty()))
            }
            "/submit" -> Parse.Ok(Command.Submit(query["selector"]?.takeIf { it.isNotBlank() }))
            else -> unknown(path)
        }
    }

    /**
     * JS for /api/v1/fill: sets the matched input/textarea's value through the prototype's
     * native setter (so framework listeners notice) and dispatches input+change. Returns
     * the string ok / not-found / unsupported, which evaluateJavascript delivers JSON-quoted
     * to the app side. A bare selector is also tried as a [name=...] match.
     */
    fun fillScript(selector: String, value: String): String {
        val sel = jsonEscape(selector)
        val valLit = jsonEscape(value)
        return "(function(){" +
            "var s=\"$sel\";" +
            "var e=null;" +
            "try{e=document.querySelector(s)}catch(x){}" +
            "if(!e){try{e=document.querySelector('[name=\"'+s+'\"]')}catch(x){}}" +
            "if(!e)return'not-found';" +
            "if(e.tagName!=='INPUT'&&e.tagName!=='TEXTAREA')return'unsupported';" +
            "var p=e.tagName==='TEXTAREA'?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;" +
            "if(!p||!Object.getOwnPropertyDescriptor(p,'value'))return'unsupported';" +
            "Object.getOwnPropertyDescriptor(p,'value').set.call(e,\"$valLit\");" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));" +
            "e.dispatchEvent(new Event('change',{bubbles:true}));" +
            "return'ok'})()"
    }

    /**
     * JS for /api/v1/submit: clicks the matched element, or with no selector submits the
     * first form via requestSubmit (runs validation and submit handlers) falling back to
     * submit(). Returns ok / not-found.
     */
    fun submitScript(selector: String?): String {
        val selLit = selector?.let { "\"${jsonEscape(it)}\"" } ?: "null"
        return "(function(){" +
            "var e=null;" +
            "try{e=$selLit?document.querySelector($selLit):document.querySelector('form')}catch(x){}" +
            "if(!e)return'not-found';" +
            "if(e.tagName==='FORM'){e.requestSubmit?e.requestSubmit():e.submit();return'ok'}" +
            "e.click?e.click():e.dispatchEvent(new Event('click',{bubbles:true}));" +
            "return'ok'})()"
    }

    /** /api/v1/info body; [versionName] comes from BuildConfig on the app side. */
    fun infoJson(versionName: String): String =
        "{\"name\":\"cookies-extractor\",\"version\":${json(versionName)}," +
            "\"api_level\":$API_LEVEL,\"capabilities\":[${CAPABILITIES.joinToString(",") { json(it) }}]}"

    /** Renders one outcome as the HTTP body. */
    fun errorJson(code: String, message: String): String =
        "{\"error\":{\"code\":${json(code)},\"message\":${json(message)}}}"

    /** JSON string literal (with quotes) for [s]; escapes the RFC 8259 set. */
    fun json(s: String): String = "\"" + jsonEscape(s) + "\""

    /**
     * Escapes a string for embedding inside a JSON (and thus JS) string literal. Control
     * characters become their four-hex-digit JSON escape so the output stays printable;
     * everything else passes through.
     */
    fun jsonEscape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    private fun unknown(path: String) =
        Parse.Err(404, "unknown_route", "no such route: $path")
}
