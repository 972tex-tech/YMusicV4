package ymusic.quickjs

/** Tests create runtimes on the real :engine-js bundle ([TEST_ENGINE_JS]) — exactly what
 *  production evals. The production entry point requires the script explicitly. */
internal suspend fun QuickJsRuntime.Companion.create(): QuickJsRuntime = create(TEST_ENGINE_JS)
