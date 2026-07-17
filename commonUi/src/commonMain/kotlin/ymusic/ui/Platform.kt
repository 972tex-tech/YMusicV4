package ymusic.ui

/** True where the primary pointer is a finger (Android/iOS). Mouse-driven platforms disable
 *  touch idioms like edge-swipe — Compose's mouse drag slop is ~0.1dp, so a whole-screen drag
 *  gesture would steal any click whose press wanders a pixel. */
internal expect val platformUsesTouch: Boolean
