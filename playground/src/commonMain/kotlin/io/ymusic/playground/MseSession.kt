package io.ymusic.playground

import io.ymusic.dynamicengine.player.DynamicEnginePlayer
import io.ymusic.mse.MseVideoSurface
import kmpcommon.log.Logger

/**
 * Engine-js + C MSE session. HTTP / cache differ per platform; the Compose screen does not.
 */
internal interface MseSession {
    val player: DynamicEnginePlayer
    val log: Logger
    suspend fun close()
}

internal expect suspend fun bootMseSession(surface: MseVideoSurface): MseSession

internal expect fun defaultMseVideoId(): String

/** iOS lab proof lines (`[ios-mse] …`); no-op on JVM. */
internal expect fun logMseLab(line: String)
