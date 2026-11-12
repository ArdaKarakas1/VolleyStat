package com.voleybolveveleybol.volleystat

/** Turkish copy for a finished upload. It reports the file check only and never implies an analysis ran. */
internal fun describeUpload(result: UploadResult): String {
    val lines = mutableListOf<String>()
    when (result.status) {
        "READY" -> {
            lines += "Video kabul edildi."
            describeMedia(result)?.let { lines += it }
            result.warnings.mapNotNullTo(lines) { warningText(it) }
            lines += "Sunucu yalnızca dosya biçimini kontrol etti. Saha, top ve kamera sabitliği ölçülmedi."
            lines += "Otomatik analiz modeli henüz bağlı değil. İstatistik üretilmedi."
        }
        "QUARANTINED" -> {
            lines += "Video analiz için kabul edilmedi."
            lines += rejectText(result.rejectCode)
            describeMedia(result)?.let { lines += it }
        }
        else -> {
            lines += "Video saklandı ama doğrulanamadı."
            lines += rejectText(result.rejectCode)
        }
    }
    return lines.joinToString("\n")
}

private fun describeMedia(result: UploadResult): String? {
    val parts = mutableListOf<String>()
    if (result.width != null && result.height != null) parts += "${result.width}×${result.height}"
    result.fps?.let { parts += "${"%.0f".format(it)} fps" }
    result.durationMs?.let { parts += formatDuration(it) }
    result.codec?.let { parts += it }
    return if (parts.isEmpty()) null else "Dosya: " + parts.joinToString(", ")
}

internal fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    return if (seconds < 60) "$seconds sn" else "${seconds / 60} dk ${seconds % 60} sn"
}

private fun warningText(code: String): String? = when (code) {
    "LOW_RESOLUTION" -> "Uyarı: Çözünürlük 720p altında. Top ve forma numaraları zor seçilir."
    "LOW_FRAME_RATE" -> "Uyarı: Kare hızı 24 fps altında. Hızlı toplar karelerin arasında kaybolabilir."
    "PORTRAIT_ORIENTATION" -> "Uyarı: Video dikey çekilmiş. Sahanın tamamı kadraja girmeyebilir."
    "VARIABLE_FRAME_RATE" -> "Uyarı: Kare hızı değişken. Zaman etiketleri kayabilir."
    else -> null
}

private fun rejectText(code: String?): String = when (code) {
    "NOT_A_VIDEO" -> "Dosya bir video değil ya da biçimi tanınmadı."
    "VIDEO_CORRUPT" -> "Dosya bozuk ya da yarım. Videoyu yeniden seçin."
    "UNSUPPORTED_CODEC" -> "Videonun kodlaması desteklenmiyor. H.264 MP4 olarak kaydedin."
    "VIDEO_DURATION_INVALID" -> "Videonun süresi okunamadı ya da 12 saati aşıyor."
    "PROBE_UNAVAILABLE" -> "Sunucuda video inceleme aracı kurulu değil."
    else -> "Neden bildirilmedi."
}
