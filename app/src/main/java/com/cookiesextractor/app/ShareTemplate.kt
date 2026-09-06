package com.cookiesextractor.app

/**
 * Pure-JVM rendering of the user-defined share-message template (COK-15). Well-formed
 * {name} tokens are replaced when the context carries the key; everything else (unknown
 * keys, spaces inside braces, unmatched braces) passes through as literal text so the
 * user sees their own typos instead of silently losing characters. No android imports
 * so it runs under plain JUnit like RedirectCapture and DebugHttp.
 */
object ShareTemplate {

    /** Context keys; the single source for the names. MainActivity.shareContext and the editor legend both build from these. */
    const val KEY_PAYLOAD = "payload"
    const val KEY_URL = "url"
    const val KEY_TITLE = "title"
    const val KEY_HOST = "host"
    const val KEY_DATE = "date"

    /** Placeholder names the share context provides; drives the editor legend. */
    val PLACEHOLDERS: List<String> = listOf(KEY_PAYLOAD, KEY_URL, KEY_TITLE, KEY_HOST, KEY_DATE)

    private val TOKEN = Regex("""\{([a-zA-Z][a-zA-Z0-9_]*)\}""")

    fun render(template: String, context: Map<String, String>): String =
        TOKEN.replace(template) { m -> context[m.groupValues[1]] ?: m.value }
}
