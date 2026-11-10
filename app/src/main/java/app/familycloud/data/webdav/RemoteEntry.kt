package app.familycloud.data.webdav

data class RemoteEntry(
    val relativePath: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long?,
    val modifiedEpochMs: Long?,
)
