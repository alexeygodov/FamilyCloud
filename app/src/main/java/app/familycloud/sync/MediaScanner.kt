package app.familycloud.sync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class LocalAlbum(val bucketId: String, val name: String)

data class LocalMedia(
    val id: Long,
    val bucketId: String,
    val albumName: String,
    val displayName: String,
    val size: Long,
    val dateModifiedMillis: Long,
    val uri: Uri,
    val video: Boolean,
)

class MediaScanner(private val context: Context) {
    fun albums(): List<LocalAlbum> {
        val found = linkedMapOf<String, String>()
        scan(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, video = false) { item ->
            found.putIfAbsent(item.bucketId, item.albumName)
        }
        scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video = true) { item ->
            found.putIfAbsent(item.bucketId, item.albumName)
        }
        return found.map { (id, name) -> LocalAlbum(id, name) }.sortedBy { it.name.lowercase() }
    }

    fun files(bucketId: String, photos: Boolean, videos: Boolean, modifiedSinceMillis: Long?): List<LocalMedia> {
        val result = mutableListOf<LocalMedia>()
        if (photos) {
            scan(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, video = false, bucketId, modifiedSinceMillis, result::add)
        }
        if (videos) {
            scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video = true, bucketId, modifiedSinceMillis, result::add)
        }
        return result
    }

    private fun scan(
        collection: Uri,
        video: Boolean,
        bucketId: String? = null,
        modifiedSinceMillis: Long? = null,
        onItem: (LocalMedia) -> Unit,
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            BUCKET_ID,
            BUCKET_NAME,
        )
        val selection = buildString {
            if (bucketId != null) append("$BUCKET_ID = ?")
            if (modifiedSinceMillis != null) {
                if (isNotEmpty()) append(" AND ")
                append("${MediaStore.MediaColumns.DATE_MODIFIED} >= ?")
            }
        }.ifEmpty { null }
        val args = buildList {
            if (bucketId != null) add(bucketId)
            if (modifiedSinceMillis != null) add((modifiedSinceMillis / 1000L).toString())
        }.toTypedArray()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val bucketCol = cursor.getColumnIndexOrThrow(BUCKET_ID)
            val albumCol = cursor.getColumnIndexOrThrow(BUCKET_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                onItem(
                    LocalMedia(
                        id = id,
                        bucketId = cursor.getString(bucketCol) ?: "",
                        albumName = cursor.getString(albumCol) ?: "",
                        displayName = cursor.getString(nameCol) ?: id.toString(),
                        size = cursor.getLong(sizeCol),
                        dateModifiedMillis = cursor.getLong(dateCol) * 1000L,
                        uri = ContentUris.withAppendedId(collection, id),
                        video = video,
                    ),
                )
            }
        }
    }

    private companion object {
        const val BUCKET_ID = "bucket_id"
        const val BUCKET_NAME = "bucket_display_name"
    }
}
