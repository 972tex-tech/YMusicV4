package io.ymusic.mediascanner

import kmpcommon.uri.Uri
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSystemDocumentsProviderTest {

    @Test
    fun non_file_scheme_is_unresolvable() = runTest {
        val provider = FileSystemDocumentsProvider(FakeFileSystem())
        assertTrue(provider.queryDocument(Uri.parse("content://auth/tree/x")) is DocumentInfo.Missing)
        assertTrue(provider.queryDocument(Uri.parse("https://example.com/a.mp3")) is DocumentInfo.Missing)
        assertTrue(provider.listChildren(Uri.parse("content://auth/tree/x")).isEmpty())
    }

    @Test
    fun file_scheme_is_resolved() = runTest {
        val fs = FakeFileSystem().apply {
            createDirectories("/music".toPath())
            write("/music/a.mp3".toPath()) { write(ByteArray(10)) }
        }
        val provider = FileSystemDocumentsProvider(fs)
        assertTrue(provider.queryDocument(Uri.parse("file:///music")) is DocumentInfo.Folder)
        val children = provider.listChildren(Uri.parse("file:///music"))
        assertEquals(listOf("a.mp3"), children.map { (it as DocumentInfo.File).name })
        // Canonical file:/// form (empty authority), matching Android's Uri.fromFile.
        assertEquals("file:///music/a.mp3", children.single().uri.toString())
    }

    @Test
    fun child_uris_percent_encode_special_characters() = runTest {
        val fs = FakeFileSystem().apply {
            createDirectories("/my music".toPath())
            write("/my music/a b.mp3".toPath()) { write(ByteArray(1)) }
        }
        val child = FileSystemDocumentsProvider(fs).listChildren(Uri.parse("file:///my%20music")).single()
        assertEquals("file:///my%20music/a%20b.mp3", child.uri.toString())
    }
}
