package com.cookiesextractor.app

import android.content.Context
import org.json.JSONArray

/**
 * Persists the user's extra-domain list for the structured cookie collection (COK-32).
 * Stored as a JSON array of URLs; unset, blank, or malformed falls back to
 * [CookieCollector.DEFAULT_EXTRA_URLS] so the Google default keeps working for everyone
 * and a bad edit can never break the share. Uses applicationContext so it never
 * retains an Activity.
 */
class ExtraUrlsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<String> {
        val json = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() } ?: return CookieCollector.DEFAULT_EXTRA_URLS
        val arr = try {
            JSONArray(json)
        } catch (e: Exception) {
            return CookieCollector.DEFAULT_EXTRA_URLS
        }
        // an empty saved list is a deliberate "none": keep it (collect from the loaded
        // URL only), unlike a missing/corrupt entry which means "default"
        return buildList {
            for (i in 0 until arr.length()) {
                runCatching { add(arr.getString(i)) }
            }
        }
    }

    /** The editable text for the dialog: one URL per line. */
    fun loadAsText(): String = load().joinToString("\n")

    fun save(urls: List<String>) {
        val arr = JSONArray()
        urls.forEach { arr.put(it) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun resetToDefault() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val PREFS = "extra_urls"
        const val KEY = "urls_json"
    }
}
