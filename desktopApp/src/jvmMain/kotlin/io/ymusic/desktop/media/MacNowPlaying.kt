package io.ymusic.desktop.media

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import kmpcommon.log.Logger

/**
 * macOS Now Playing backend: publishes to `MPNowPlayingInfoCenter` (Control Center widget, lock
 * screen) and receives `MPRemoteCommandCenter` transport commands (media keys F7–F9, widget
 * buttons, AirPods taps). Pure JNA over the Objective-C runtime — no bundled native code.
 *
 * `objc_msgSend` is deliberately invoked as a FIXED-arg function per message signature (Apple's
 * "cast objc_msgSend to the right type" rule) — that is the correct ABI on both x86_64 and arm64;
 * JNA's `Function.invoke*` does exactly that.
 *
 * Command handlers arrive on an AppKit thread: they only forward to [MediaSessionCommands], which
 * the wiring re-dispatches to the app main thread.
 */
internal class MacNowPlayingSession(commands: MediaSessionCommands, private val log: Logger? = null) : DesktopMediaSession {

    private val objc = NativeLibrary.getInstance("objc")
    private val msgSend: Function = objc.getFunction("objc_msgSend")
    private val getClass: Function = objc.getFunction("objc_getClass")
    private val selRegister: Function = objc.getFunction("sel_registerName")

    // Loading the framework registers its Objective-C classes with the runtime.
    private val mediaPlayer =
        NativeLibrary.getInstance("/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer")

    /** IMP callbacks must stay strongly referenced for the target class's lifetime. */
    private val imps = mutableListOf<Callback>()
    private val target: Pointer

    init {
        target = makeCommandTarget(commands)
        val center = send(cls("MPRemoteCommandCenter"), "sharedCommandCenter")!!
        hook(center, "togglePlayPauseCommand", "ymusicToggle:")
        hook(center, "playCommand", "ymusicPlay:")
        hook(center, "pauseCommand", "ymusicPause:")
        hook(center, "nextTrackCommand", "ymusicNext:")
        hook(center, "previousTrackCommand", "ymusicPrev:")
        hook(center, "changePlaybackPositionCommand", "ymusicSeek:")
    }

    override fun update(state: MediaSessionState) {
        val center = send(cls("MPNowPlayingInfoCenter"), "defaultCenter")!!
        if (!state.hasItem) {
            msgSend.invokeVoid(arrayOf(center, sel("setNowPlayingInfo:"), null))
            msgSend.invokeVoid(arrayOf(center, sel("setPlaybackState:"), STATE_STOPPED))
            return
        }
        val info = send(cls("NSMutableDictionary"), "dictionary")!!
        state.title?.let { dictPut(info, constant("MPMediaItemPropertyTitle"), nsString(it)) }
        state.artist?.let { dictPut(info, constant("MPMediaItemPropertyArtist"), nsString(it)) }
        state.durationMs?.let {
            dictPut(info, constant("MPMediaItemPropertyPlaybackDuration"), nsNumber(it / 1000.0))
        }
        dictPut(info, constant("MPNowPlayingInfoPropertyElapsedPlaybackTime"), nsNumber(state.positionMs / 1000.0))
        dictPut(
            info,
            constant("MPNowPlayingInfoPropertyPlaybackRate"),
            nsNumber(if (state.playing) state.speed.toDouble() else 0.0),
        )
        msgSend.invokeVoid(arrayOf(center, sel("setNowPlayingInfo:"), info))
        msgSend.invokeVoid(arrayOf(center, sel("setPlaybackState:"), if (state.playing) STATE_PLAYING else STATE_PAUSED))
    }

    override fun release() {
        val commandCenter = send(cls("MPRemoteCommandCenter"), "sharedCommandCenter")!!
        listOf(
            "togglePlayPauseCommand", "playCommand", "pauseCommand",
            "nextTrackCommand", "previousTrackCommand", "changePlaybackPositionCommand",
        ).forEach { prop ->
            send(commandCenter, prop)?.let { msgSend.invokeVoid(arrayOf(it, sel("removeTarget:"), target)) }
        }
        val center = send(cls("MPNowPlayingInfoCenter"), "defaultCenter")!!
        msgSend.invokeVoid(arrayOf(center, sel("setNowPlayingInfo:"), null))
        msgSend.invokeVoid(arrayOf(center, sel("setPlaybackState:"), STATE_STOPPED))
    }

    // --- command target: a runtime-built NSObject subclass whose IMPs are JNA callbacks ---

    /** IMP shape for `- (MPRemoteCommandHandlerStatus)handle:(MPRemoteCommandEvent *)event`. */
    internal fun interface CommandImp : Callback {
        fun invoke(self: Pointer?, cmd: Pointer?, event: Pointer?): Long
    }

    private fun makeCommandTarget(commands: MediaSessionCommands): Pointer {
        val allocPair = objc.getFunction("objc_allocateClassPair")
        val addMethod = objc.getFunction("class_addMethod")
        val registerPair = objc.getFunction("objc_registerClassPair")
        // One process-global class; a second session (tests) reuses it with fresh IMPs? No — IMPs
        // bind at class level, so the class name carries a nonce and each session owns its class.
        val name = "YMusicRemoteCommandTarget${System.nanoTime()}"
        val cls = allocPair.invokePointer(arrayOf(cls("NSObject"), name, 0L))
            ?: error("objc_allocateClassPair failed")

        fun method(selName: String, body: (Pointer?) -> Unit) {
            val imp = CommandImp { _, _, event ->
                // An OS media-key press that throws must not vanish — the key "did nothing".
                runCatching { body(event) }
                    .onFailure { log?.log(Logger.Level.WARN, "media command $selName failed", it) }
                COMMAND_HANDLED
            }
            imps += imp
            addMethod.invoke(arrayOf(cls, sel(selName), imp, "q@:@"))
        }
        method("ymusicToggle:") { commands.togglePlay() }
        method("ymusicPlay:") { commands.play() }
        method("ymusicPause:") { commands.pause() }
        method("ymusicNext:") { commands.next() }
        method("ymusicPrev:") { commands.previous() }
        method("ymusicSeek:") { event ->
            if (event != null) {
                val seconds = msgSend.invokeDouble(arrayOf(event, sel("positionTime")))
                commands.seekTo((seconds * 1000).toLong())
            }
        }
        registerPair.invokeVoid(arrayOf(cls))
        return send(send(cls, "alloc")!!, "init")!!
    }

    private fun hook(commandCenter: Pointer, commandProp: String, selName: String) {
        val command = send(commandCenter, commandProp) ?: return
        msgSend.invokeVoid(arrayOf(command, sel("setEnabled:"), true))
        msgSend.invokeVoid(arrayOf(command, sel("addTarget:action:"), target, sel(selName)))
    }

    // --- objc plumbing ---

    private fun cls(name: String): Pointer = getClass.invokePointer(arrayOf(name)) ?: error("no objc class $name")

    private fun sel(name: String): Pointer = selRegister.invokePointer(arrayOf(name))

    private fun send(receiver: Pointer, selector: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(receiver, sel(selector), *args))

    /** An exported `NSString *const` from the MediaPlayer framework (e.g. the info-dict keys). */
    private fun constant(symbol: String): Pointer =
        mediaPlayer.getGlobalVariableAddress(symbol).getPointer(0)

    /** UTF-8 bytes, not a JNA String arg — String marshalling follows jna.encoding, not UTF-8. */
    private fun nsString(value: String): Pointer =
        send(cls("NSString"), "stringWithUTF8String:", value.toByteArray(Charsets.UTF_8) + 0)!!

    private fun nsNumber(value: Double): Pointer = send(cls("NSNumber"), "numberWithDouble:", value)!!

    private fun dictPut(dict: Pointer, key: Pointer, value: Pointer) {
        msgSend.invokeVoid(arrayOf(dict, sel("setObject:forKey:"), value, key))
    }

    /** Read-back helper for tests: `nowPlayingInfo[key]` as a Kotlin value, null when absent. */
    internal fun publishedValue(symbol: String): Any? {
        val center = send(cls("MPNowPlayingInfoCenter"), "defaultCenter")!!
        val info = send(center, "nowPlayingInfo") ?: return null
        val value = send(info, "objectForKey:", constant(symbol)) ?: return null
        val isString = msgSend.invokeLong(arrayOf(value, sel("isKindOfClass:"), cls("NSString"))) != 0L
        return if (isString) {
            send(value, "UTF8String")?.getString(0, "UTF-8")
        } else {
            msgSend.invokeDouble(arrayOf(value, sel("doubleValue")))
        }
    }

    /** Read-back helper for tests: the published MPNowPlayingPlaybackState. */
    internal fun publishedPlaybackState(): Long {
        val center = send(cls("MPNowPlayingInfoCenter"), "defaultCenter")!!
        return msgSend.invokeLong(arrayOf(center, sel("playbackState")))
    }

    private companion object {
        const val COMMAND_HANDLED = 0L // MPRemoteCommandHandlerStatusSuccess
        const val STATE_PLAYING = 1L // MPNowPlayingPlaybackState
        const val STATE_PAUSED = 2L
        const val STATE_STOPPED = 3L
    }
}
