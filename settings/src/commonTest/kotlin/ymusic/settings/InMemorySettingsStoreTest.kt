package ymusic.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class InMemorySettingsStoreTest {
    @Test
    fun defaultsWhenUnset() {
        val store = InMemorySettingsStore()
        assertEquals("en", store.string("hl", "en").value)
        assertEquals(true, store.boolean("flag", true).value)
        assertEquals(42, store.int("count", 42).value)
    }

    @Test
    fun putUpdatesTypedViews() =
        runTest {
            val store = InMemorySettingsStore()
            val hl = store.string("hl", "en")
            val flag = store.boolean("flag", false)
            val count = store.int("count", 0)

            store.putString("hl", "vi")
            store.putBoolean("flag", true)
            store.putInt("count", 7)

            assertEquals("vi", hl.value)
            assertEquals(true, flag.value)
            assertEquals(7, count.value)
        }

    @Test
    fun collectorSeesChangesButNotUnrelatedKeys() =
        runTest {
            val store = InMemorySettingsStore()
            val emissions = mutableListOf<String>()
            val job =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    store.string("hl", "en").toList(emissions)
                }

            store.putString("hl", "vi")
            store.putString("other", "x")
            store.putString("hl", "fr")
            job.cancel()

            assertEquals(listOf("en", "vi", "fr"), emissions)
        }

    @Test
    fun appSettingsAccessors() =
        runTest {
            val store = InMemorySettingsStore()
            assertEquals("", store.flow(AppSettings.hl).value)
            assertEquals("", store.flow(AppSettings.gl).value)
            assertEquals("", store.flow(AppSettings.themeAccent).value)

            store.put(AppSettings.hl, "vi")
            assertEquals("vi", store.flow(AppSettings.hl).value)
        }
}
