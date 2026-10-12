package io.ymusic.ios

import io.ymusic.ui.IncomingLinks

/** A ymusic:// url the app was opened with (SwiftUI onOpenURL). */
fun iosOpenUrl(url: String) {
    IncomingLinks.open(url)
}
