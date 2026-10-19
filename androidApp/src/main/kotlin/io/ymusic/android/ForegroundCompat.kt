package io.ymusic.android

import android.app.Service
import androidx.core.app.ServiceCompat
import kmpcommon.log.Logger

/**
 * [ServiceCompat.stopForeground] that survives a system_server bug: some Android 13 builds (seen on
 * TCL) throw a NullPointerException back from `ActiveServices.dropFgsNotificationStateLocked` while
 * demoting a valid foreground service. Playback must go on; the next demote or stopSelf settles it.
 */
internal fun Service.stopForegroundSafely(flags: Int, log: Logger?) {
    try {
        ServiceCompat.stopForeground(this, flags)
    } catch (e: NullPointerException) {
        log?.log(Logger.Level.WARN, "stopForeground failed inside system_server", e)
    }
}
