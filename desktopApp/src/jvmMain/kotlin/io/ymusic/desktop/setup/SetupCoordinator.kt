package io.ymusic.desktop.setup

import kmpcommon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** What one first-run component is doing, as the checklist shows it. */
sealed interface ComponentState {
    data object Pending : ComponentState

    /**
     * [fraction] is 0..1, or null while there is no measurable progress yet. [done]/[total] are
     * bytes when the source reports them, so a row without byte counts shows none rather than
     * inventing a size to divide by.
     */
    data class Working(val fraction: Float?, val done: Long = 0, val total: Long = 0) : ComponentState

    data object Ready : ComponentState

    /** The download failed. The row keeps its place and grows a retry. */
    data class Failed(val reason: String) : ComponentState

    /** Not here, and not something the app can install — the user is given the command. */
    data object Absent : ComponentState
}

/**
 * The first-run checklist as a value: ffmpeg the app fetches itself, and the music folder the user
 * may choose once tags can be read.
 */
data class SetupState(
    val mediaTools: ComponentState = ComponentState.Pending,
    val musicFolder: String? = null,
) {
    /**
     * Whether the first step is passable. Only the components the app can actually install itself
     * hold the user here — an [ComponentState.Absent] one is waved through deliberately: a build
     * with no tools manifest configured cannot ever satisfy it, and a first run that can never be
     * finished is worse than one that starts with a feature missing.
     */
    val canContinue: Boolean
        get() = mediaTools.settled

    private val ComponentState.settled: Boolean
        get() = this is ComponentState.Ready || this is ComponentState.Absent

    /** Ffmpeg reads the tags, so choosing a folder before it lands would scan into nothing. */
    val canChooseFolder: Boolean get() = mediaTools is ComponentState.Ready
}

/**
 * Drives the first run: the ffmpeg download starts and reports into [state]; everything else is a
 * user action ([retryMediaTools], [chooseMusicFolder]).
 *
 * Each component is independent — one failing never stops another, and a retry re-runs only its own
 * step, which is why they are separate jobs rather than one sequence.
 */
class SetupCoordinator(
    private val scope: CoroutineScope,
    private val ffmpeg: FfmpegProvisioner,
    /** An ffmpeg already on this machine (dev checkout, `brew install ffmpeg`) — no download then. */
    private val existingFfmpeg: () -> File?,
    /** Opens the folder chooser; returns the chosen path, or null when the user backed out. */
    private val pickFolder: suspend () -> String?,
    private val logger: Logger? = null,
) {
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state

    private val log get() = logger?.fork("setup")

    private var toolsJob: Job? = null

    /** Kick the ffmpeg download off. Safe to call once per window. */
    fun start() {
        retryMediaTools()
    }

    fun retryMediaTools() {
        if (toolsJob?.isActive == true) return
        toolsJob = scope.launch {
            runComponent({ _state.update { s -> s.copy(mediaTools = it) } }) {
                when {
                    existingFfmpeg() != null -> ComponentState.Ready
                    !ffmpeg.isEnabled -> ComponentState.Absent
                    else -> {
                        ffmpeg.provision { done, total ->
                            val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                            _state.update { it.copy(mediaTools = ComponentState.Working(fraction, done, total)) }
                        }
                        ComponentState.Ready
                    }
                }
            }
        }
    }

    fun chooseMusicFolder() {
        scope.launch {
            pickFolder()?.let { path -> _state.update { it.copy(musicFolder = path) } }
        }
    }

    /**
     * Runs one component's step, reporting Working → Ready, or Failed with something a person can
     * act on. Cancellation is not a failure: the window closing must not paint a red row on the way
     * out.
     */
    private suspend fun runComponent(publish: (ComponentState) -> Unit, step: suspend () -> ComponentState) {
        publish(ComponentState.Working(null))
        try {
            publish(step())
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            log?.captureError(t, "first-run component failed")
            publish(ComponentState.Failed(t.message ?: t.toString()))
        }
    }
}
