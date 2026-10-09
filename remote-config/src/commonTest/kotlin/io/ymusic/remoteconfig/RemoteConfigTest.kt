package io.ymusic.remoteconfig

import kmphttp.HttpStatusException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

private val SERVER = RemoteConfigData(
    variableA = 7,
    featureX = true,
    appUpdate = AppUpdate(1300, "4.1.0", "notes", "https://x/y.msi", 12, "ab", "sig-app"),
    playerJs = BundleVersion(version = 123, url = "https://x/player.js", signature = "sig-player"),
    engineJs = BundleVersion(version = 11, url = "https://x/engine.js", signature = "sig-engine"),
    updatedAt = 0L, // stamped by the fake at fetch time, the way the real service does
)

/**
 * The seam [RemoteConfig] depends on. These tests cover its POLICY — throttle, cache, freshness,
 * what a failure leaves behind; how the service is reached belongs to HttpApiServiceTest (jvm).
 */
private class FakeApi(private val now: () -> Long) : ApiService {
    var calls = 0
    var failWith: Exception? = null
    var data: RemoteConfigData = SERVER

    override suspend fun getConfig(): RemoteConfigData {
        calls++
        failWith?.let { throw it }
        return data.copy(updatedAt = now())
    }

    override suspend fun pulse(events: List<PulseEvent>) = throw UnsupportedOperationException("not under test here")

    override suspend fun openDeviceLink(): String = throw UnsupportedOperationException()

    override suspend fun putDeviceLink(id: String, payload: String): Boolean = throw UnsupportedOperationException()

    override suspend fun takeDeviceLink(id: String): String? = throw UnsupportedOperationException()

    override suspend fun closeDeviceLink(id: String) = throw UnsupportedOperationException()
}

class RemoteConfigTest {

    /** Advanced by hand, so staleness is decided by the test rather than by how long one takes. */
    private var clock = 1_000_000L

    private fun api() = FakeApi(now = { clock })

    private fun config(
        api: ApiService?,
        scope: CoroutineScope,
        fs: FileSystem = FakeFileSystem(),
        cache: Path = "/cache/remote-config.json".toPath(),
    ) = RemoteConfig(
        api = api,
        cacheFile = cache,
        fileSystem = fs,
        scope = scope,
        now = { clock },
    )

    @Test
    fun a_fetch_replaces_the_defaults() = runTest {
        val rc = config(api(), this)

        val data = rc.getConfig()

        assertEquals(7, data.variableA)
        assertEquals(true, data.featureX)
        assertEquals(123L, data.playerJs?.version)
        assertEquals(11L, data.engineJs?.version)
        assertEquals("https://x/y.msi", data.appUpdate?.url)
        assertEquals(clock, data.updatedAt)
    }

    @Test
    fun a_fresh_value_is_served_without_another_request() = runTest {
        val api = api()
        val rc = config(api, this)
        rc.getConfig()

        rc.getConfig()

        assertEquals(1, api.calls, "still inside the window")
    }

    @Test
    fun a_stale_value_is_refetched() = runTest {
        val api = api()
        val rc = config(api, this)
        rc.getConfig()

        clock += REMOTE_CONFIG_MIN_INTERVAL_MS + 1
        rc.getConfig()

        assertEquals(2, api.calls)
    }

    @Test
    fun the_cache_round_trips_through_the_file_including_its_age() = runTest {
        val fs = FakeFileSystem()
        val cachePath = "/cache/remote-config.json".toPath()
        config(api(), this, fs, cachePath).getConfig()
        assertEquals(true, fs.exists(cachePath))

        // A second instance over the same file is the next launch of the app.
        val nextApi = api()
        val data = config(nextApi, this, fs, cachePath).getConfig()

        assertEquals(7, data.variableA)
        assertEquals(123L, data.playerJs?.version)
        assertEquals("https://x/y.msi", data.appUpdate?.url)
        assertEquals(clock, data.updatedAt, "the age survives the restart, so a launch need not refetch")
        assertEquals(0, nextApi.calls, "served from the cache, not the service")
    }

    @Test
    fun a_corrupt_cache_does_not_stop_the_fetch() = runTest {
        val fs = FakeFileSystem()
        val cachePath = "/cache/remote-config.json".toPath()
        fs.createDirectories(cachePath.parent!!)
        fs.write(cachePath) { writeUtf8("{not json") }

        val api = api()
        val data = config(api, this, fs, cachePath).getConfig()

        assertEquals(7, data.variableA)
        assertEquals(1, api.calls)
    }

    @Test
    fun a_failed_fetch_with_no_cache_falls_back_to_the_defaults() = runTest {
        val api = api().apply { failWith = IOException("offline") }

        assertEquals(RemoteConfigData.DEFAULTS, config(api, this).getConfig())
    }

    @Test
    fun a_failed_fetch_keeps_the_last_good_values() = runTest {
        val api = api()
        val rc = config(api, this)
        rc.getConfig()

        api.failWith = IOException("network down")
        clock += REMOTE_CONFIG_MIN_INTERVAL_MS + 1

        assertEquals(
            7,
            rc.getConfig().variableA,
            "an outage must not change how the app behaves",
        )
    }

    @Test
    fun an_http_refusal_keeps_the_last_good_values() = runTest {
        val api = api()
        val rc = config(api, this)
        rc.getConfig()

        api.failWith = HttpStatusException(500)
        clock += REMOTE_CONFIG_MIN_INTERVAL_MS + 1

        assertEquals(7, rc.getConfig().variableA)
    }

    @Test
    fun a_failure_outside_the_contract_is_survived_too() = runTest {
        // The seam promises HttpStatusException/IOException/parse errors, but RemoteConfig must
        // not fall over if an implementation leaks something else.
        val api = api().apply { failWith = IllegalStateException("bug in the impl") }

        assertEquals(RemoteConfigData.DEFAULTS, config(api, this).getConfig())
    }

    @Test
    fun no_service_means_defaults_and_no_request() = runTest {
        assertEquals(RemoteConfigData.DEFAULTS, config(api = null, scope = this).getConfig())
    }

    @Test
    fun checkUpdate_does_nothing_while_the_value_is_fresh() = runTest {
        val api = api()
        val rc = config(api, this)
        rc.getConfig()

        rc.checkUpdate()
        rc.checkUpdate()

        assertEquals(1, api.calls)
    }
}
