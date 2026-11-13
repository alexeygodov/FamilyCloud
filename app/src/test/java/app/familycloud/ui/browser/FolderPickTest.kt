package app.familycloud.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderPickTest {
    @Test
    fun moveIsBlockedForTheSourceAndForAFolderInsideTheMovedOne() {
        val moved = listOf("photos/2024/")
        assertFalse(canPlaceHere(PickMode.Move, "photos", "photos", moved))
        assertFalse(canPlaceHere(PickMode.Move, "photos/2024", "photos", moved))
        assertFalse(canPlaceHere(PickMode.Move, "photos/2024/june", "photos", moved))
        assertTrue(canPlaceHere(PickMode.Move, "archive", "photos", moved))
        assertTrue(canPlaceHere(PickMode.Copy, "photos", "photos", moved))
        assertTrue(canPlaceHere(PickMode.Album, "photos", "", emptyList()))
    }
}
