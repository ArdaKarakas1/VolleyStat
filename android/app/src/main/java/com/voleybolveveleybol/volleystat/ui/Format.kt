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

/** "12 Kas 21:40". */
fun formatMoment(epochMs: Long): String = SimpleDateFormat("d MMM HH:mm", turkish).format(Date(epochMs))

fun formatFps(fps: Double): String =
    if (fps % 1.0 == 0.0) fps.toLong().toString() else String.format(turkish, "%.2f", fps)

fun percentOf(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)
