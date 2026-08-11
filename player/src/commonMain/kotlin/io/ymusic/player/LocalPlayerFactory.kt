package io.ymusic.player

/**
 * Platform glue needed to build a [LocalPlayer]. Android carries an application [android.content.Context];
 * every other platform is empty. Constructed by the app's platform layer and handed to
 * [createLocalPlayer].
 */
expect class LocalPlayerContext

/** Build a platform [LocalPlayer]. Call on the main thread — the returned player is main-thread-only. */
expect fun createLocalPlayer(context: LocalPlayerContext): LocalPlayer
