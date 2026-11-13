package app.familycloud.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.familycloud.R
import app.familycloud.sync.TransferKeys
import app.familycloud.ui.theme.Dimens
import app.familycloud.ui.theme.FamilyCloudTheme

data class TransferBarState(
    val label: String,
    val fraction: Float?,
    val uploading: Boolean,
    val onCancel: (() -> Unit)?,
)

@Composable
fun rememberWorkTransfer(uniqueName: String): TransferBarState? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = androidx.compose.runtime.remember(uniqueName) { WorkManager.getInstance(context) }
    val infos by manager.getWorkInfosForUniqueWorkFlow(uniqueName)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: return null
    val done = info.progress.getInt(TransferKeys.DONE, 0)
    val total = info.progress.getInt(TransferKeys.TOTAL, 0)
    val sent = info.progress.getLong(TransferKeys.SENT, 0L)
    val size = info.progress.getLong(TransferKeys.SIZE, 0L)
    val name = info.progress.getString(TransferKeys.NAME).orEmpty()
    val current = if (total > 0) (done + 1).coerceAtMost(total) else 0
    val label = when {
        total > 0 && name.isNotEmpty() -> stringResource(R.string.transfer_upload, current, total, name)
        total > 0 -> stringResource(R.string.transfer_upload_short, current, total)
        else -> stringResource(R.string.sync_working)
    }
    val fraction = when {
        size > 0L -> (sent.toFloat() / size.toFloat()).coerceIn(0f, 1f)
        total > 0 -> done.toFloat() / total.toFloat()
        else -> null
    }
    return TransferBarState(
        label = label,
        fraction = fraction,
        uploading = true,
        onCancel = { manager.cancelUniqueWork(uniqueName) },
    )
}

@Composable
fun TransferBar(
    label: String,
    fraction: Float?,
    uploading: Boolean,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Dimens.l, vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (uploading) Icons.Filled.CloudUpload else Icons.Filled.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f).padding(horizontal = Dimens.m)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fraction == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Dimens.xs))
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.xs),
                )
                Text(
                    stringResource(R.string.sync_file_percent, (fraction * 100).toInt()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onCancel != null) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, stringResource(R.string.cancel))
            }
        }
    }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TransferBarPreview() {
    FamilyCloudTheme(dynamicColor = false) {
        TransferBar(
            label = stringResource(R.string.transfer_upload, 3, 7, "IMG_2041.jpg"),
            fraction = 0.42f,
            uploading = true,
            onCancel = {},
        )
    }
}
