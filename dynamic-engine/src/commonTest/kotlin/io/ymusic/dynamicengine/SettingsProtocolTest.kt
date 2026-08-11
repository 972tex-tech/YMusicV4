package io.ymusic.dynamicengine

import io.ymusic.settings.InMemorySettingsStore
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsProtocolTest {
    private suspend fun DynamicEngine.drive(head: String): String {
        val pipe = openPipe("test/settings")
        return try {
            val input = Buffer().writeUtf8(head)
            pipe.sink.send(input, input.size)
            val output = Buffer()
            while (pipe.source.receive(output, 4096L) != -1L) { }
            output.readUtf8()
        } finally {
            pipe.sink.close()
            pipe.source.close()
        }
    }

    private suspend fun engine(settings: InMemorySettingsStore) =
        createTestEngine(FakeHttpClient(CannedResponse.ofText("")), settings = settings)

    @Test fun missing_values_return_the_callers_typed_defaults() = runTest {
        val engine = engine(InMemorySettingsStore())
        try {
            assertEquals("string:fallback", engine.drive("""{"key":"s","type":"string","default":"fallback"}"""))
            assertEquals("boolean:true", engine.drive("""{"key":"b","type":"boolean","default":true}"""))
            assertEquals("int:42", engine.drive("""{"key":"i","type":"int","default":42}"""))
        } finally {
            engine.close()
        }
    }

    @Test fun writes_are_visible_through_the_same_store() = runTest {
        val settings = InMemorySettingsStore()
        val engine = engine(settings)
        try {
            assertEquals("string:new", engine.drive("""{"key":"s","type":"string","value":"new","default":"old"}"""))
            assertEquals("boolean:false", engine.drive("""{"key":"b","type":"boolean","value":false,"default":true}"""))
            assertEquals("int:7", engine.drive("""{"key":"i","type":"int","value":7,"default":0}"""))
            assertEquals("new", settings.string("s", "").value)
            assertEquals(false, settings.boolean("b", true).value)
            assertEquals(7, settings.int("i", 0).value)
        } finally {
            engine.close()
        }
    }
}
