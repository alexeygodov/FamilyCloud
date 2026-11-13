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
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(epochMs).atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val date = time.toLocalDate()
    val clock = DateTimeFormatter.ofPattern("HH:mm", locale).format(time)
    return when {
        date == today -> clock
        date == today.minusDays(1) -> stringResource(R.string.date_yesterday, clock)
        date.year == today.year -> DateTimeFormatter.ofPattern("d MMM, HH:mm", locale).format(time)
        else -> DateTimeFormatter.ofPattern("dd.MM.yyyy", locale).format(time)
    }
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
