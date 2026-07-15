package com.cookiesextractor.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists bookmarks to SharedPreferences as a JSON array. Bookmarks are keyed by URL:
 * adding an existing URL updates its title rather than creating a duplicate. Uses
 * applicationContext so it never retains an Activity. Survives app restarts.
 */
class BookmarksRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<Bookmark> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        val arr = try {
            JSONArray(json)
        } catch (e: Exception) {
            return emptyList()
        }
        // Parse entries resiliently: one malformed entry must not discard the rest.
        return buildList {
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    add(Bookmark(o.optString(KEY_TITLE), o.optString(KEY_URL)))
                } catch (e: Exception) {
                    // skip this entry, keep the others
                }
            }
        }
    }

    /** Adds or updates a bookmark for [bookmark.url]; returns the resulting list. */
    fun add(bookmark: Bookmark): List<Bookmark> {
        val updated = load().filterNot { it.url == bookmark.url } + bookmark
        save(updated)
        return updated
    }

    /** Removes the bookmark matching [bookmark.url]; returns the resulting list. */
    fun remove(bookmark: Bookmark): List<Bookmark> {
        val updated = load().filterNot { it.url == bookmark.url }
        save(updated)
        return updated
    }

    private fun save(bookmarks: List<Bookmark>) {
        val arr = JSONArray()
        bookmarks.forEach {
            arr.put(JSONObject().put(KEY_TITLE, it.title).put(KEY_URL, it.url))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "bookmarks"
        private const val KEY = "bookmarks_json"
        private const val KEY_TITLE = "title"
        private const val KEY_URL = "url"
    }
}
