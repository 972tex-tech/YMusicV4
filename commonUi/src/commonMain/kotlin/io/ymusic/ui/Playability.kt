package io.ymusic.ui

import io.ymusic.player.PlayerError
import projectcommon.error.Playability
import projectcommon.error.playabilityOf

internal fun PlayerError.playability(): Playability? = playabilityOf(code)
