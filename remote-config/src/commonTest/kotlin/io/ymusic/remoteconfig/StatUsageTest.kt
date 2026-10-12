package io.ymusic.remoteconfig

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Records what [StatUsage] delivers; getConfig is not under test here. */
private class FakePulseApi : ApiService {
    val batches = mutableListOf<List<PulseEvent>>()
    var failWith: Exception? = null

    override suspend fun getConfig(): RemoteConfigData = throw UnsupportedOperationException()

    override suspend fun pulse(events: List<PulseEvent>) {
        failWith?.let { throw it }
        batches += events
    }

    override suspend fun openDeviceLink(): String = throw UnsupportedOperationException()

    override suspend fun putDeviceLink(id: String, payload: String): Boolean = throw UnsupportedOperationException()

    override suspend fun takeDeviceLink(id: String): String? = throw UnsupportedOperationException()

    override suspend fun closeDeviceLink(id: String) = throw UnsupportedOperationException()

    override suspend fun getSkipSegments(videoId: String): List<SkipSegment> = throw UnsupportedOperationException()
}

class StatUsageTest {

    private var clock = 1_000_000_000L
    private var enabled = true
    private val queuePath = "/stats/queue.ndjson".toPath()

    private fun stats(
        api: ApiService?,
        scope: CoroutineScope,
        fs: FileSystem = FakeFileSystem(),
        queue: Path = queuePath,
    ) = StatUsage(
        api = api,
        enabled = { enabled },
        queueFile = queue,
        fileSystem = fs,
        scope = scope,
        now = { clock },
    )

    @Test
    fun disabled_means_nothing_happens_at_all() = runTest {
        val api = FakePulseApi()
        val fs = FakeFileSystem()
        enabled = false

        stats(api, this, fs).onAppStart()
        testScheduler.advanceUntilIdle()

        assertEquals(0, api.batches.size)
        assertFalse(fs.exists(queuePath))
    }

    @Test
    fun the_first_event_of_a_launch_ships_immediately() = runTest {
        val api = FakePulseApi()
        val fs = FakeFileSystem()
        stats(api, this, fs).onAppStart()

        testScheduler.runCurrent()

        assertEquals(listOf("app_start"), api.batches.single().map { it.name })
        assertEquals(clock, api.batches.single().single().at)
        assertFalse(fs.exists(queuePath), "sent events do not linger on disk")
    }

    @Test
    fun a_later_event_flushes_once_the_interval_has_passed() = runTest {
        val api = FakePulseApi()
        val fs = FakeFileSystem()
        val rig = stats(api, this, fs)
        rig.onAppStart()
        testScheduler.runCurrent()

        rig.record("play")
        testScheduler.runCurrent()
        assertEquals(1, api.batches.size, "inside the interval: queued, no request, no timer")
        assertTrue(fs.exists(queuePath), "but it is already safe on disk")

        clock += STAT_FLUSH_INTERVAL_MS
        rig.record("search")
        testScheduler.runCurrent()

        assertEquals(listOf("play", "search"), api.batches[1].map { it.name })
        assertFalse(fs.exists(queuePath))
    }

    @Test
    fun a_full_batch_schedules_the_flush_for_the_interval_end() = runTest {
        val api = FakePulseApi()
        val rig = stats(api, this)
        rig.record("first")
        testScheduler.runCurrent()

        repeat(STAT_FLUSH_BATCH) { rig.record("play") }
        testScheduler.runCurrent()
        assertEquals(1, api.batches.size, "a burst does not jump the interval")

        clock += STAT_FLUSH_INTERVAL_MS + 1
        testScheduler.advanceTimeBy(STAT_FLUSH_INTERVAL_MS + 1)
        testScheduler.runCurrent()

        assertEquals(STAT_FLUSH_BATCH, api.batches[1].size, "and it drains by itself, no further event")
    }

    @Test
    fun recording_appends_one_line_per_event_without_rewriting() = runTest {
        val fs = FakeFileSystem()
        // Offline, so nothing drains and the file shows the raw append-only shape.
        val rig = stats(FakePulseApi().apply { failWith = IOException("offline") }, this, fs)

        rig.record("play")
        rig.record("search")
        rig.record("play")
        testScheduler.runCurrent()

        val lines = fs.read(queuePath) { readUtf8() }.trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") }, "one JSON object per line")
    }

    @Test
    fun a_failed_send_queues_and_the_next_launch_ships_the_backlog() = runTest {
        val api = FakePulseApi().apply { failWith = IOException("offline") }
        val fs = FakeFileSystem()
        val firstStart = clock
        stats(api, this, fs).onAppStart() // first-of-launch: attempted immediately, fails
        testScheduler.runCurrent()
        assertTrue(fs.exists(queuePath), "unsent events wait on disk")

        api.failWith = null
        clock += 1000
        // A new instance over the same file is the next launch; its first event ships at once.
        stats(api, this, fs).onAppStart()
        testScheduler.runCurrent()

        val batch = api.batches.single()
        assertEquals(listOf("app_start", "app_start"), batch.map { it.name })
        assertEquals(listOf(firstStart, firstStart + 1000), batch.map { it.at }, "queued events keep their time")
        assertFalse(fs.exists(queuePath))
    }

    @Test
    fun a_backlog_drains_in_chunks_the_server_will_accept() = runTest {
        val api = FakePulseApi().apply { failWith = IOException("offline") }
        val fs = FakeFileSystem()
        val offlineRig = stats(api, this, fs)
        repeat(41) { offlineRig.record("play") }
        testScheduler.advanceUntilIdle()

        api.failWith = null
        stats(api, this, fs).record("play")
        testScheduler.runCurrent() // first-of-launch: immediate, carrying the whole backlog

        assertEquals(listOf(15, 15, 12), api.batches.map { it.size }, "oldest first, never over the wire cap")
        assertFalse(fs.exists(queuePath))
    }

    @Test
    fun a_broken_line_is_skipped_and_the_lines_around_it_survive() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories(queuePath.parent!!)
        // The user-specified shape: valid, torn, valid — each event is one line.
        fs.write(queuePath) {
            writeUtf8("""{"name":"hello","at":1}""" + "\n")
            writeUtf8("{broeknLIne\n")
            writeUtf8("""{"name":"world","at":2}""" + "\n")
        }

        val api = FakePulseApi()
        stats(api, this, fs).record("play")
        testScheduler.runCurrent() // first-of-launch: immediate

        assertEquals(listOf("hello", "world", "play"), api.batches.single().map { it.name })
    }

    @Test
    fun properties_round_trip_through_the_disk_queue() = runTest {
        val api = FakePulseApi().apply { failWith = IOException("offline") }
        val fs = FakeFileSystem()
        stats(api, this, fs).record("play", mapOf("source" to "search", "kind" to "video"))
        testScheduler.runCurrent()

        api.failWith = null
        stats(api, this, fs).record("play")
        testScheduler.runCurrent()

        assertEquals(mapOf("source" to "search", "kind" to "video"), api.batches.single().first().properties)
    }

    @Test
    fun no_service_means_nothing_recorded() = runTest {
        val fs = FakeFileSystem()

        stats(api = null, scope = this, fs = fs).onAppStart()
        testScheduler.advanceUntilIdle()

        assertFalse(fs.exists(queuePath))
    }
}
