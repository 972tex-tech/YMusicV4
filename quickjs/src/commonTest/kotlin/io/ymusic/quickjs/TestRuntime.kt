package io.ymusic.quickjs

import kotlinx.coroutines.withContext

/** The ONE engine thread every test runtime is confined to — the production pattern (a
 *  caller-owned dispatcher shared across runtime lifetimes), process-lifetime so tests need no
 *  per-runtime dispatcher teardown. */
internal val testEngineDispatcher = QuickJsRuntime.newDispatcher()

/** Tests create runtimes on the real :engine-js bundle ([TEST_ENGINE_JS]) — exactly what
 *  production evals. The production entry point requires the script and dispatcher explicitly;
 *  these wrappers supply the shared test dispatcher and hop onto it, since the synchronous
 *  create must run ON its engine thread. */
internal suspend fun QuickJsRuntime.Companion.create(): QuickJsRuntime = create(TEST_ENGINE_JS)

internal suspend fun QuickJsRuntime.Companion.create(
    script: String,
    appVersion: Int = 0,
    onLog: ((level: Int, message: String) -> Unit)? = null,
    configure: (QuickJsRuntime) -> Unit = {},
): QuickJsRuntime = withContext(testEngineDispatcher) {
    create(script, appVersion, testEngineDispatcher, onLog, configure)
}

internal suspend fun QuickJsRuntime.Companion.create(
    bytecode: ByteArray,
    appVersion: Int = 0,
    onLog: ((level: Int, message: String) -> Unit)? = null,
    configure: (QuickJsRuntime) -> Unit = {},
): QuickJsRuntime = withContext(testEngineDispatcher) {
    create(bytecode, appVersion, testEngineDispatcher, onLog, configure)
}
