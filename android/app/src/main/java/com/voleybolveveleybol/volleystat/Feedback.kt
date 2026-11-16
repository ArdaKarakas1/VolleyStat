package com.voleybolveveleybol.volleystat

import android.content.Intent
import android.net.Uri

/** The owner's address for feedback. The app shows it, so it stands in the public repository too. */
const val FEEDBACK_ADDRESS = "voleybolveveleybol@gmail.com"

/**
 * A mail to the owner, opened in the phone's mail app. It carries the app version and the phone
 * model and nothing from a match: no video, no team and no player.
 */
fun feedbackIntent(versionName: String, phone: String, androidVersion: Int): Intent =
    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_ADDRESS))
        putExtra(Intent.EXTRA_SUBJECT, "VolleyStat geri bildirimi")
        putExtra(Intent.EXTRA_TEXT, "Uygulama sürümü: $versionName\nTelefon: $phone, Android $androidVersion\n\n")
    }
