package io.ymusic.mediastore

import io.ymusic.mediastore.model.BucketDraft
import kmpcommon.uri.Uri
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * upsertBucket must not re-root an existing child when the draft's parentId is null (the
 * BucketDraft default) — mirrors updateItem's COALESCE(:bucketId, bucketId) guard.
 */
class UpsertBucketParentTest {

    @Test
    fun reupsert_without_parent_keeps_the_existing_parent() = runTest {
        val driver = mediaStoreJdbcDriver(":memory:")
        val store = BetterMediaStore.create(driver)
        try {
            val parent = store.upsertBucket(BucketDraft(uri = Uri.parse("file:///music"), name = "music"))
            val child = store.upsertBucket(
                BucketDraft(uri = Uri.parse("file:///music/rock"), name = "rock", parentId = parent.id),
            )
            assertEquals(parent.id, child.parentId)

            // Re-upsert the child by uri only (parentId defaults to null) — must NOT re-root it.
            val reupserted = store.upsertBucket(BucketDraft(uri = Uri.parse("file:///music/rock"), name = "rock"))
            assertEquals(child.id, reupserted.id)
            assertEquals(parent.id, reupserted.parentId, "null parentId must not detach the child")
        } finally {
            driver.close()
        }
    }
}
