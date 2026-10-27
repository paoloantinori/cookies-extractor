package com.cookiesextractor.app

/** A saved bookmark: a user-facing title and the URL it loads. */
data class Bookmark(val title: String, val url: String)

/**
 * Display rules for the bookmarks sheet (COK-36): the sort order and the quick-search
 * filter. Pure JVM, no android imports, so the rules run under plain JUnit. Sort is by
 * the user-facing label (title if present, else the URL), case-insensitive, URL as the
 * tie-break; the filter matches a case-insensitive substring against both the label
 * and the URL.
 */
object BookmarksView {

    fun label(bm: Bookmark): String = bm.title.ifBlank { bm.url }

    fun sorted(items: List<Bookmark>): List<Bookmark> =
        items.sortedWith(compareBy({ label(it).lowercase() }, { it.url.lowercase() }))

    fun filter(items: List<Bookmark>, query: String): List<Bookmark> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return items
        return items.filter { label(it).lowercase().contains(q) || it.url.lowercase().contains(q) }
    }
}
