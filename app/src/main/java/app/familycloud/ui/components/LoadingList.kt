package app.familycloud.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import app.familycloud.ui.theme.Dimens
import app.familycloud.ui.theme.FamilyCloudTheme

@Composable
fun LoadingList(
    modifier: Modifier = Modifier,
    rows: Int = 8,
) {
    Column(modifier.fillMaxWidth()) {
        repeat(rows) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.l, vertical = Dimens.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(Dimens.listIcon)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(Modifier.padding(start = Dimens.l).weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(if (index % 2 == 0) 0.72f else 0.5f)
                            .height(Dimens.m)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Box(
                        Modifier
                            .padding(top = Dimens.s)
                            .fillMaxWidth(0.36f)
                            .height(Dimens.s)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
        }
    }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LoadingListPreview() {
    FamilyCloudTheme(dynamicColor = false) {
        LoadingList(rows = 4)
    }
}
