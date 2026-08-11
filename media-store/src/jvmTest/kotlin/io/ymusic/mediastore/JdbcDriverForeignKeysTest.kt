package io.ymusic.mediastore

import io.ymusic.mediastore.model.BucketDraft
import io.ymusic.mediastore.model.MediaItemDraft
import kmpcommon.uri.Uri
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rest of the JVM suite runs on ":memory:", which keeps ONE JDBC connection — masking that a
 * file-backed driver opens a fresh connection per statement/transaction, each starting with
 * SQLite's default `foreign_keys=OFF`. This test runs the cascade scenario against a real file,
 * exactly like production, so the pragma has to hold on every connection for it to pass.
 */
class JdbcDriverForeignKeysTest {

    @Test
    fun file_backed_database_enforces_foreign_keys() = runTest {
        val file = File.createTempFile("media-store-fk", ".db").apply { delete() }
        try {
            val driver = mediaStoreJdbcDriver(file.absolutePath)
            val store = BetterMediaStore.create(driver)
            try {
                val bucket = store.upsertBucket(
                    BucketDraft(uri = Uri.parse("file:///music"), name = "music"),
                )
                val child = store.upsertBucket(
                    BucketDraft(uri = Uri.parse("file:///music/rock"), name = "rock", parentId = bucket.id),
                )
                val item = store.upsertItem(
                    bucket.id,
                    MediaItemDraft(
                        uri = Uri.parse("file:///music/a.mp3"),
                        title = "a",
                        mimeType = "audio/mpeg",
                        fileSize = 10,
                        lastModified = 1_000L,
                    ),
                )

                store.deleteBucket(bucket.id, hard = true)

                // Without per-connection foreign_keys, the bucket row vanishes but the ON DELETE
                // cascades never run: the item and child bucket survive as live orphans.
                assertNull(store.findItem(item.id), "hard delete must cascade to the bucket's items")
                assertNull(store.findBucket(child.id), "hard delete must cascade to child buckets")
                val fresh = store.upsertBucket(BucketDraft(uri = Uri.parse("file:///music"), name = "music"))
                assertTrue(fresh.id > bucket.id, "old bucket row must be truly gone")
            } finally {
                driver.close()
            }
        } finally {
            file.delete()
        }
    }
}
