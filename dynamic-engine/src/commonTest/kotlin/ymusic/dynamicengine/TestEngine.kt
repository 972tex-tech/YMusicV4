package ymusic.dynamicengine

import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import kotlinx.coroutines.withContext
import ymusic.dynamicengine.internal.ENGINE_TEST_JS
import ymusic.quickjs.QuickJsRuntime
import ymusic.settings.SettingsStore

/** The ONE JS thread every test engine is confined to — the manager's prod pattern (engines share
 *  a dispatcher across their lifetimes), process-lifetime so tests need no per-engine teardown. */
internal val testEngineDispatcher = QuickJsRuntime.newDispatcher()

/** Tests run on the :engine-js TEST bundle (production + pipe-driven fixtures). Smoke tests that
 *  deliberately target the production artifact call [DynamicEngine.create] directly. */
internal suspend fun createTestEngine(
    client: KmpHttpClient,
    appVersion: Int = 1,
    logger: Logger? = null,
    cacheStore: CacheStore? = null,
    settings: SettingsStore? = null,
): DynamicEngine = withContext(testEngineDispatcher) {
    DynamicEngine.create(client, appVersion, testEngineDispatcher, logger, ENGINE_TEST_JS, cacheStore, settings = settings)
}
