package kmpffmpeg

/**
 * Non-macOS Apple backend (iOS / tvOS / watchOS): a no-op. These platforms do not permit an app to
 * spawn child processes (no `fork`/`exec`), so [startProcess] throws. The module is still part of the
 * full target matrix so consumers with these targets can depend on it; they just can't launch here.
 */
internal actual fun startProcess(builder: KmpProcessBuilder): KmpProcess =
    throw UnsupportedOperationException("child processes are not permitted on this platform")
