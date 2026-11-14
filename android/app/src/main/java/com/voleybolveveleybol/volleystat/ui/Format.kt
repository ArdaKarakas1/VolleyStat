package com.voleybolveveleybol.volleystat.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val turkish = Locale.forLanguageTag("tr-TR")

/** "312 MB", "1,2 GB". Decimal megabytes, the unit phones show for files. */
fun formatBytes(bytes: Long): String {
    val megabytes = bytes / 1_000_000.0
    return if (megabytes < 1000) {
        "${megabytes.toLong().coerceAtLeast(if (bytes > 0) 1 else 0)} MB"
    } else {
        String.format(turkish, "%.1f GB", megabytes / 1000)
    }
}

/** "45 sn", "1 dk 23 sn", "1 sa 30 dk". */
fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    return when {
        seconds < 60 -> "$seconds sn"
        seconds < 3600 -> "${seconds / 60} dk ${seconds % 60} sn"
        else -> "${seconds / 3600} sa ${seconds % 3600 / 60} dk"
    }
}

/** "12 Kasım 2026" from an ISO date; the raw text when it is not a date. */
fun formatDay(isoDate: String): String = runCatching {
    java.time.LocalDate.parse(isoDate).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", turkish))
}.getOrDefault(isoDate)

/** "12:36" or "1:02:36" for a running recording or a clip position. */
fun formatClock(durationMs: Long): String {
    val seconds = durationMs / 1000
    val hours = seconds / 3600
    val rest = String.format(turkish, "%02d:%02d", seconds % 3600 / 60, seconds % 60)
    return if (hours > 0) "$hours:$rest" else rest
}

/** "12:36,4": a position in the video to a tenth of a second, for placing the boundary of a rally. */
fun formatClockTenths(positionMs: Long): String = "${formatClock(positionMs)},${positionMs % 1000 / 100}"

/** "12 Kas 21:40". */
fun formatMoment(epochMs: Long): String = SimpleDateFormat("d MMM HH:mm", turkish).format(Date(epochMs))

fun formatFps(fps: Double): String =
    if (fps % 1.0 == 0.0) fps.toLong().toString() else String.format(turkish, "%.2f", fps)

fun percentOf(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)
