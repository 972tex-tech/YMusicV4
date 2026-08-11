package ymusic.desktop

import kotlinx.coroutines.runBlocking
import ymusic.desktop.media.MacNowPlayingSession
import ymusic.desktop.media.MediaSessionCommands
import ymusic.desktop.media.MediaSessionState
import java.io.File
import java.util.Base64
import kotlin.math.sin

/**
 * Headless diagnostic for the macOS Now Playing bridge: registers the command handlers, publishes
 * a playing state, and prints every command the OS delivers for ~60s. Drive it from another
 * terminal with real media keys, the Control Center widget, or `MRMediaRemoteSendCommand`.
 *
 *   ./gradlew :desktopApp:nowPlayingProbe                    — bridge alone
 *   ./gradlew :desktopApp:nowPlayingProbe -PnpArgs=cef       — plus a JCEF browser playing a
 *     near-silent tone, to prove Chromium (with HardwareMediaKeyHandling disabled) no longer
 *     takes over the process-global MPRemoteCommandCenter while page media plays.
 */
fun main(args: Array<String>) {
    // AWT boots the AppKit run loop; MPRemoteCommand events are delivered on the main queue.
    java.awt.Toolkit.getDefaultToolkit()
    val session = MacNowPlayingSession(
        object : MediaSessionCommands {
            override fun togglePlay() = println("PROBE cmd: togglePlay")

            override fun play() = println("PROBE cmd: play")

            override fun pause() = println("PROBE cmd: pause")

            override fun next() = println("PROBE cmd: next")

            override fun previous() = println("PROBE cmd: previous")

            override fun seekTo(positionMs: Long) = println("PROBE cmd: seekTo $positionMs")
        },
    )
    if (args.firstOrNull() == "cef") {
        val cef = CefManager()
        // The probe's own profile — sharing the app's dir yields to a running app (profile lock).
        val client = runBlocking {
            cef.createClient(cacheDir = File(System.getProperty("user.home"), ".ymusic/jcef-probe-cache"))
        }
        client.createBrowser(stealPageUrl(), true, false).createImmediately()
        println("PROBE cef browser started")
        Thread.sleep(6_000) // let the page play long enough for Chromium's media session to engage
    }
    session.update(
        MediaSessionState(
            title = "Probe Song",
            artist = "YMusic probe",
            playing = true,
            positionMs = 10_000,
            durationMs = 60_000,
            speed = 1f,
            hasItem = true,
        ),
    )
    println("PROBE ready (now-playing published); listening 60s")
    Thread.sleep(60_000)
    session.release()
    println("PROBE done")
}

/** A page that plays a looping ~inaudible tone with a MediaSession — what a stealing Chromium
 *  would latch onto. Audio amplitude is ~2% so the probe stays effectively silent. */
private fun stealPageUrl(): String {
    val html = """
        <audio id="a" loop autoplay src="data:audio/wav;base64,${toneWavBase64()}"></audio>
        <script>
          navigator.mediaSession.metadata = new MediaMetadata({ title: 'CEF steal probe' });
          navigator.mediaSession.setActionHandler('play', () => {});
          navigator.mediaSession.setActionHandler('pause', () => {});
          document.getElementById('a').play();
        </script>
    """.trimIndent()
    return "data:text/html;base64," + Base64.getEncoder().encodeToString(html.toByteArray())
}

/** One second of 440Hz at ~2% amplitude, 8-bit mono 8kHz PCM WAV. */
private fun toneWavBase64(): String {
    val rate = 8000
    val samples = ByteArray(rate) { i -> (128 + 3 * sin(2 * Math.PI * 440 * i / rate)).toInt().toByte() }
    val dataLen = samples.size
    val header = java.io.ByteArrayOutputStream().apply {
        fun le32(v: Int) = write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        write("RIFF".toByteArray()); le32(36 + dataLen); write("WAVE".toByteArray())
        write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(rate); le32(rate); le16(1); le16(8)
        write("data".toByteArray()); le32(dataLen)
    }.toByteArray()
    return Base64.getEncoder().encodeToString(header + samples)
}
