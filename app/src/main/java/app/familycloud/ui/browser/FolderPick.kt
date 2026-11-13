package app.familycloud.ui.browser

enum class PickMode { Move, Copy, Album }

fun parsePickMode(value: String): PickMode = when (value) {
    "copy" -> PickMode.Copy
    "album" -> PickMode.Album
    else -> PickMode.Move
}

fun canPlaceHere(mode: PickMode, destination: String, source: String, blocked: List<String>): Boolean {
    if (mode != PickMode.Move) return true
    val dest = destination.trim('/')
    if (dest == source.trim('/')) return false
    return blocked.none { folder ->
        val root = folder.trim('/')
        root.isNotEmpty() && (dest == root || dest.startsWith("$root/"))
    }
}
