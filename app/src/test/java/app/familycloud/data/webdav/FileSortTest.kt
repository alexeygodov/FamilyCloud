package app.familycloud.data.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSortTest {
    @Test
    fun foldersStayAboveFilesWhenSortingByNameDescending() {
        val sorted = sortEntries(
            listOf(
                file("a.jpg", "a.jpg"),
                folder("z", "z/"),
            ),
            SortOrder(SortField.Name, ascending = false),
        )
        assertEquals(listOf("z/", "a.jpg"), sorted.map { it.relativePath })
    }

    @Test
    fun dateDescendingPutsTheNewestFileFirstAndLeavesUnknownDatesLast() {
        val sorted = sortEntries(
            listOf(
                file("old.jpg", "old.jpg", modified = 10),
                file("new.jpg", "new.jpg", modified = 50),
                file("none.jpg", "none.jpg", modified = null),
            ),
            SortOrder(SortField.Date, ascending = false),
        )
        assertEquals(listOf("new.jpg", "old.jpg", "none.jpg"), sorted.map { it.relativePath })
    }

    @Test
    fun sizeAscendingPutsUnknownSizesLast() {
        val sorted = sortEntries(
            listOf(
                file("big.jpg", "big.jpg", size = 500),
                file("small.jpg", "small.jpg", size = 5),
                file("none.jpg", "none.jpg", size = null),
            ),
            SortOrder(SortField.Size, ascending = true),
        )
        assertEquals(listOf("small.jpg", "big.jpg", "none.jpg"), sorted.map { it.relativePath })
    }

    private fun file(name: String, path: String, size: Long? = 1L, modified: Long? = 1L) = RemoteEntry(
        relativePath = path,
        name = name,
        isFolder = false,
        size = size,
        modifiedEpochMs = modified,
    )

    private fun folder(name: String, path: String) = RemoteEntry(
        relativePath = path,
        name = name,
        isFolder = true,
        size = null,
        modifiedEpochMs = null,
    )
}
