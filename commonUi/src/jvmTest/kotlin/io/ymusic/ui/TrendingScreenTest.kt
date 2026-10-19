package io.ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ymusic.dynamicengine.yt.YtSectionHeader
import java.util.Locale
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TrendingScreenTest {

    /** A newer bundle inventing a chart this build has no string for still shows its title. */
    @Test
    fun the_engine_synthesized_title_is_the_fallback_for_an_unknown_key() {
        Locale.setDefault(Locale.ENGLISH)
        runComposeUiTest {
            setContent {
                SectionHeader(
                    YtSectionHeader(title = "Some Future Chart", titleKey = "chart_from_a_newer_bundle"),
                )
            }
            waitUntilAtLeastOneExists(hasText("Some Future Chart"), 10_000)
        }
    }
}
