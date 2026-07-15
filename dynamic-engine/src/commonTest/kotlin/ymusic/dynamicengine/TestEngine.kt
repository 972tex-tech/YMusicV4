package ymusic.dynamicengine

import kmpcommon.log.Logger
import kmphttp.KmpHttpClient
import ymusic.dynamicengine.internal.ENGINE_TEST_JS

/** Tests run on the :engine-js TEST bundle (production + pipe-driven fixtures). Smoke tests that
 *  deliberately target the production artifact call [DynamicEngine.create] directly. */
internal suspend fun createTestEngine(
    client: KmpHttpClient,
    appVersion: Int = 1,
    logger: Logger? = null,
    cacheStore: CacheStore? = null,
): DynamicEngine = DynamicEngine.create(client, appVersion, logger, ENGINE_TEST_JS, cacheStore)
