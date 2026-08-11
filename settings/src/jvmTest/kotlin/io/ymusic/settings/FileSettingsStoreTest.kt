package io.ymusic.settings

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class FileSettingsStoreTest {
    @Test
    fun persistsAndReloads() =
        runTest {
            val fs = FakeFileSystem()
            val path = "/prefs/app.properties".toPath()

            val store = FileSettingsStore.open(path, fs)
            store.putString("hl", "vi")
            store.putBoolean("flag", true)
            store.putInt("count", 3)

            val reloaded = FileSettingsStore.open(path, fs)
            assertEquals("vi", reloaded.string("hl", "en").value)
            assertEquals(true, reloaded.boolean("flag", false).value)
            assertEquals(3, reloaded.int("count", 0).value)
        }

    @Test
    fun opensCleanWhenFileMissing() =
        runTest {
            val store = FileSettingsStore.open("/prefs/none.properties".toPath(), FakeFileSystem())
            assertEquals("en", store.string("hl", "en").value)
        }
}
