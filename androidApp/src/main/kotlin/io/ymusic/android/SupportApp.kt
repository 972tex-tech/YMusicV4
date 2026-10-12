package io.ymusic.android

import android.app.Activity
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

    /**
     * Its donation screen; or, when it is missing or too old to have one, its Play Store page.
     * Started from [activity], in YMusic's own task: the dialog is translucent and sits over the app.
     */
    fun openDonation(activity: Activity) {
        // Its intent filter has no CATEGORY_DEFAULT, so neither resolveActivity nor an implicit
        // start finds it: query without that requirement and start it by class name.
        val donation = activity.packageManager.queryIntentActivities(Intent(ACTION_DONATE).setPackage(PACKAGE), 0).firstOrNull()
        if (donation != null) {
            activity.startActivity(Intent(ACTION_DONATE).setClassName(donation.activityInfo.packageName, donation.activityInfo.name))
            return
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")))
        } catch (_: ActivityNotFoundException) {
            // No Play Store app on this device: its web page instead.
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE")))
        }
    }
}
