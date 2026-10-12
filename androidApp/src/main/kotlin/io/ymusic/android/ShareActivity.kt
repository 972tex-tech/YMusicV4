package io.ymusic.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.ymusic.ui.LinkAction
import io.ymusic.ui.LinkPlayChooser

/**
 * The share-sheet targets (YouTube's Share → YMusic): one activity behind four aliases — Play (asks
 * play / play single / queue next / queue last), MP3 download, M4A download, Other download (the
 * options dialog). It only picks the link out of the shared text and the action, then hands both to
 * [MainActivity], whose App resolves and runs them.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = sharedUrl(intent)
        if (url == null) {
            Toast.makeText(this, R.string.share_no_link, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val action = when (intent.component?.className?.substringAfterLast('.')) {
            "ShareDownloadMp3" -> LinkAction.DOWNLOAD_MP3
            "ShareDownloadM4a" -> LinkAction.DOWNLOAD_M4A
            "ShareDownload" -> LinkAction.DOWNLOAD
            else -> null
        }
        if (action != null) {
            forward(url, action)
            return
        }
        val settings = PrefsSettingsStore.open(this)
        setContent {
            LinkPlayChooser(settings) { picked ->
                if (picked != null) forward(url, picked) else finish()
            }
        }
    }

    private fun forward(url: String, action: LinkAction) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_LINK)
                .setData(Uri.parse(url))
                .putExtra(MainActivity.EXTRA_LINK_ACTION, action.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    /** The first web link in the shared text (YouTube shares "https://youtu.be/…?si=…", other apps
     *  wrap it in a sentence); schemeless matches get https. */
    private fun sharedUrl(intent: Intent): String? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val matcher = Patterns.WEB_URL.matcher(text)
        if (!matcher.find()) return null
        val match = matcher.group()
        return if (match.contains("://")) match else "https://$match"
    }
}
