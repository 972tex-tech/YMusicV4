package io.ymusic.mediastore

import app.cash.sqldelight.db.QueryResult
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * findItemByVideoId / findItemsByVideoId filter on youtubeVid, and playlistItemDetails resolves
 * each membership code that way — one lookup per playlist entry. Without an index every lookup is
 * a full table scan (O(playlist x table)). Assert the planner actually uses the index.
 */
class YoutubeVidIndexTest {

    @Test
    fun youtubeVid_lookup_uses_the_index() {
        val driver = mediaStoreJdbcDriver(":memory:")
        try {
            val plan = StringBuilder()
            driver.executeQuery(
                identifier = null,
                sql = "EXPLAIN QUERY PLAN SELECT id FROM mediaItem WHERE youtubeVid = ?",
                mapper = { cursor ->
                    while (cursor.next().value) {
                        // column 3 (0-based) of EXPLAIN QUERY PLAN is the human-readable detail
                        plan.append(cursor.getString(3)).append('\n')
                    }
                    QueryResult.Unit
                },
                parameters = 1,
                binders = { bindString(0, "abc") },
            )
            assertTrue(
                plan.contains("mediaItem_youtubeVid"),
                "expected the youtubeVid index in the plan, got: $plan",
            )
        } finally {
            driver.close()
        }
    }
}
