package io.ymusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal expect fun DeviceLinkQrCode(payload: String, modifier: Modifier = Modifier)
