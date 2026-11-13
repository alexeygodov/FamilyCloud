package app.familycloud.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import app.familycloud.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun formatEpoch(epochMs: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", locale)
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}

@Composable
fun formatByteSize(bytes: Long): String {
    if (bytes < 1024) return stringResource(R.string.size_bytes, bytes)
    val units = listOf(
        stringResource(R.string.unit_kb),
        stringResource(R.string.unit_mb),
        stringResource(R.string.unit_gb),
    )
    var value = bytes.toDouble()
    var unit = units.first()
    for (candidate in units) {
        value /= 1024.0
        unit = candidate
        if (value < 1024) break
    }
    return stringResource(R.string.size_unit, value, unit)
}
