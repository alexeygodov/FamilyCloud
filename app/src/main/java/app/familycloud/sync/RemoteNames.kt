package app.familycloud.sync

data class UploadedSnapshot(
    val size: Long,
    val dateModified: Long,
    val incomplete: Boolean = false,
)

sealed interface NameDecision {
    data class Upload(val fileName: String) : NameDecision
    data object AlreadyPresent : NameDecision
}

fun needsUpload(existing: UploadedSnapshot?, size: Long, dateModified: Long): Boolean {
    if (existing == null || existing.incomplete) return true
    return existing.size != size || existing.dateModified != dateModified
}

fun decideRemoteName(
    original: String,
    existingSizes: Map<String, Long>,
    localSize: Long,
): NameDecision {
    val lower = existingSizes.mapKeys { it.key.lowercase() }
    var index = 0
    while (index < 10_000) {
        val candidate = suffixedName(original, index)
        val remoteSize = lower[candidate.lowercase()]
        if (remoteSize == null) return NameDecision.Upload(candidate)
        if (remoteSize == localSize) return NameDecision.AlreadyPresent
        index += 1
    }
    return NameDecision.Upload(suffixedName(original, index))
}

fun partName(fileName: String): String {
    val dot = fileName.lastIndexOf('.')
    return if (dot > 0) {
        fileName.substring(0, dot) + ".part" + fileName.substring(dot)
    } else {
        "$fileName.part"
    }
}

internal fun suffixedName(original: String, index: Int): String {
    if (index == 0) return original
    val dot = original.lastIndexOf('.')
    return if (dot > 0) {
        original.substring(0, dot) + " ($index)" + original.substring(dot)
    } else {
        "$original ($index)"
    }
}
