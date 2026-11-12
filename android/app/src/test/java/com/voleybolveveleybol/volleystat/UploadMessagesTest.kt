package com.voleybolveveleybol.volleystat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadMessagesTest {
    private fun result(status: String, reject: String? = null, warnings: List<String> = emptyList(), known: Boolean = true) =
        UploadResult("v1", status, reject, if (known) 1280 else null, if (known) 720 else null,
            if (known) 30.0 else null, if (known) 83_000L else null, if (known) "h264" else null, warnings)

    @Test fun readyVideoNeverClaimsAnalysis() {
        val text = describeUpload(result("READY", warnings = listOf("LOW_FRAME_RATE")))
        assertTrue(text.contains("Dosya: 1280×720, 30 fps, 1 dk 23 sn, h264"))
        assertTrue(text.contains("Kare hızı 24 fps altında"))
        assertTrue(text.contains("İstatistik üretilmedi"))
    }

    @Test fun quarantinedVideoExplainsTheReason() {
        val text = describeUpload(result("QUARANTINED", reject = "UNSUPPORTED_CODEC"))
        assertTrue(text.startsWith("Video analiz için kabul edilmedi."))
        assertTrue(text.contains("H.264 MP4"))
    }

    @Test fun missingMetadataIsOmittedNotShownAsZero() {
        val text = describeUpload(result("QUARANTINED", reject = "NOT_A_VIDEO", known = false))
        assertFalse(text.contains("Dosya:"))
        assertFalse(text.contains("0"))
    }

    @Test fun durations() {
        assertEquals("59 sn", formatDuration(59_999))
        assertEquals("90 dk 0 sn", formatDuration(5_400_000))
    }
}
