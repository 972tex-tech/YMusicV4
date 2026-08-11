package io.ymusic.playground

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ymusic.player.LocalPlayer

/**
 * Renders the video output of [player] (nothing for audio-only files / audio-only mode). Each
 * platform binds its native video view: Desktop → a Swing panel over the VLCJ video component;
 * Android → a `SurfaceView` handed to ExoPlayer; iOS/macOS → an `AVPlayerLayer` view.
 */
@Composable
expect fun PlayerSurface(player: LocalPlayer, modifier: Modifier = Modifier)
