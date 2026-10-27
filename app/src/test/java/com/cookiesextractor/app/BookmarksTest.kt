package com.cookiesextractor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarksTest {

    private val a = Bookmark("IBM Software", "https://app.slack.com/client/E1/C1")
    private val b = Bookmark("", "https://cloud-native.slack.com/") // blank title: URL is the label
    private val c = Bookmark("accounts", "https://accounts.google.com/")
    private val all = listOf(a, b, c)

    // ---- label ----

    @Test
    fun blankTitleFallsBackToUrlAsLabel() {
        assertEquals("https://cloud-native.slack.com/", BookmarksView.label(b))
        assertEquals("IBM Software", BookmarksView.label(a))
    }

    // ---- sorted ----

    @Test
    fun sortsByLabelCaseInsensitive() {
        // 'h' sorts before 'i': the URL label of b precedes the titled a
        assertEquals(
            listOf("accounts", "https://cloud-native.slack.com/", "IBM Software"),
            BookmarksView.sorted(all).map { BookmarksView.label(it) },
        )
    }

    @Test
    fun equalLabelsTieBreakOnUrl() {
        val x = Bookmark("same", "https://b.example/")
        val y = Bookmark("same", "https://a.example/")
        assertEquals(
            listOf("https://a.example/", "https://b.example/"),
            BookmarksView.sorted(listOf(x, y)).map { it.url },
        )
    }

    @Test
    fun sortedKeepsInputUntouched() {
        BookmarksView.sorted(all)
        assertEquals(listOf(a, b, c), all)
    }

    // ---- filter ----

    @Test
    fun blankQueryReturnsEverythingUnfiltered() {
        assertEquals(3, BookmarksView.filter(all, "   ").size)
    }

    @Test
    fun filterMatchesTitleAndUrlCaseInsensitively() {
        assertEquals(listOf(a), BookmarksView.filter(all, "ibm software"))
        assertEquals(listOf(b), BookmarksView.filter(all, "CLOUD-NATIVE"))
        assertEquals(listOf(c), BookmarksView.filter(all, "accounts.google"))
    }

    @Test
    fun noMatchYieldsEmpty() {
        assertTrue(BookmarksView.filter(all, "zzz-not-there").isEmpty())
    }
}
