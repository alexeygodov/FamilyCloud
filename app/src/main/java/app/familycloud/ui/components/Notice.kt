package app.familycloud.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.familycloud.R
import app.familycloud.ui.theme.Dimens
import app.familycloud.ui.theme.FamilyCloudTheme

enum class NoticeTone { Warning, Error }

@Composable
fun Notice(
    tone: NoticeTone,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val container = when (tone) {
        NoticeTone.Warning -> MaterialTheme.colorScheme.tertiaryContainer
        NoticeTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (tone) {
        NoticeTone.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
        NoticeTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    val icon = when (tone) {
        NoticeTone.Warning -> Icons.Filled.WarningAmber
        NoticeTone.Error -> Icons.Filled.ErrorOutline
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(
            Modifier.padding(start = Dimens.l, end = Dimens.s, top = Dimens.s, bottom = Dimens.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Column(
                Modifier.weight(1f).padding(horizontal = Dimens.m, vertical = Dimens.s),
                verticalArrangement = Arrangement.spacedBy(Dimens.xs),
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, stringResource(R.string.notice_dismiss))
                }
            }
        }
    }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NoticePreview() {
    FamilyCloudTheme(dynamicColor = false) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.m), modifier = Modifier.padding(Dimens.l)) {
            Notice(
                tone = NoticeTone.Warning,
                text = stringResource(R.string.sync_battery),
                actionLabel = stringResource(R.string.sync_battery_action),
                onAction = {},
                onDismiss = {},
            )
            Notice(
                tone = NoticeTone.Error,
                text = stringResource(R.string.error_unauthorized_short),
                actionLabel = stringResource(R.string.retry),
                onAction = {},
            )
        }
    }
}
