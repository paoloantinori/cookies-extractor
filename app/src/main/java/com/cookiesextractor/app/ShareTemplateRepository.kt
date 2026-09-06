package com.cookiesextractor.app

import android.content.Context

/**
 * Persists the user's share-message template (COK-15). load() returns null when nothing
 * (or only whitespace) is stored, meaning "use the default share strings"; the caller
 * owns the fallback so the default output stays byte-identical without being copied
 * into prefs. Uses applicationContext so it never retains an Activity.
 */
class ShareTemplateRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): String? = prefs.getString(KEY_TEMPLATE, null)?.takeIf { it.isNotBlank() }

    fun save(template: String) {
        prefs.edit().putString(KEY_TEMPLATE, template).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TEMPLATE).apply()
    }

    private companion object {
        const val PREFS = "share_template"
        const val KEY_TEMPLATE = "template"
    }
}
