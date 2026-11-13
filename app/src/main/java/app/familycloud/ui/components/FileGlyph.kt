package app.familycloud.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.familycloud.R
import app.familycloud.data.webdav.FileKind
import app.familycloud.data.webdav.kindOf
import app.familycloud.ui.theme.Dimens
import app.familycloud.ui.theme.FamilyCloudTheme
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest

enum class GlyphKind { Folder, Image, Video, Document, Archive, Audio, File }

fun glyphKind(name: String, folder: Boolean): GlyphKind {
    if (folder) return GlyphKind.Folder
    val extension = name.substringAfterLast('.', "").lowercase()
    return when {
        kindOf(name) == FileKind.Image -> GlyphKind.Image
        kindOf(name) == FileKind.Video -> GlyphKind.Video
        extension in DOCUMENTS -> GlyphKind.Document
        extension in ARCHIVES -> GlyphKind.Archive
        extension in AUDIO -> GlyphKind.Audio
        else -> GlyphKind.File
    }
}

@Composable
fun FileGlyph(
    name: String,
    folder: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    previewUrl: String? = null,
) {
    val kind = glyphKind(name, folder)
    if (kind == GlyphKind.Image && previewUrl != null) {
        val context = LocalContext.current
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context).data(previewUrl).size(256, 256).build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(Dimens.listIcon)
                .clip(MaterialTheme.shapes.small),
            loading = { GlyphCircle(GlyphKind.Image, contentDescription) },
            error = { GlyphCircle(GlyphKind.Image, contentDescription) },
        )
    } else {
        GlyphCircle(kind, contentDescription, modifier)
    }
}

@Composable
private fun GlyphCircle(
    kind: GlyphKind,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val background = when (kind) {
        GlyphKind.Folder -> MaterialTheme.colorScheme.primaryContainer
        GlyphKind.Image -> MaterialTheme.colorScheme.secondaryContainer
        GlyphKind.Video -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val tint = when (kind) {
        GlyphKind.Folder -> MaterialTheme.colorScheme.onPrimaryContainer
        GlyphKind.Image -> MaterialTheme.colorScheme.onSecondaryContainer
        GlyphKind.Video -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier.size(Dimens.listIcon).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyphIcon(kind), contentDescription, tint = tint)
    }
}

private fun glyphIcon(kind: GlyphKind): ImageVector = when (kind) {
    GlyphKind.Folder -> Icons.Filled.Folder
    GlyphKind.Image -> Icons.Filled.Image
    GlyphKind.Video -> Icons.Filled.Movie
    GlyphKind.Document -> Icons.Filled.Description
    GlyphKind.Archive -> Icons.Filled.FolderZip
    GlyphKind.Audio -> Icons.Filled.AudioFile
    GlyphKind.File -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private val DOCUMENTS = setOf("pdf", "doc", "docx", "txt", "rtf", "odt")
private val ARCHIVES = setOf("zip", "rar", "7z", "tar", "gz")
private val AUDIO = setOf("mp3", "m4a", "wav", "flac", "ogg", "aac")

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FileGlyphPreview() {
    FamilyCloudTheme(dynamicColor = false) {
        FileGlyph("notes.pdf", folder = false, contentDescription = stringResource(R.string.kind_file))
    }
}
