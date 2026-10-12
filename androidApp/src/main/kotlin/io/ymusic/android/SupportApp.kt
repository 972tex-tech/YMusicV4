package io.ymusic.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * The "Support Development" app, sold on Google Play: having it installed is premium, and it hosts
 * the donation screen. Nothing is verified — installed is enough.
 */
internal object SupportApp {
    const val PACKAGE = "com.kdev.ymusic.support"

    /** Its DonationActivity's action (YMusicLegacy's ACTION_SHOW_DONATION_DIALOG). */
    private const val ACTION_DONATE = "io.ymusic.android.plugin.ACTION_4"

    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Its donation screen; or, when it is missing or too old to have one, its Play Store page. */
    fun openDonation(context: Context) {
        val donate = Intent(ACTION_DONATE).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (donate.resolveActivity(context.packageManager) != null) {
            context.startActivity(donate)
            return
        }
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(store)
        } catch (_: ActivityNotFoundException) {
            // No Play Store app on this device: its web page instead.
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
