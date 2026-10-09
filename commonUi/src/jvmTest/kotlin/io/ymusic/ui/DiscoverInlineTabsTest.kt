package io.ymusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * On the wide layout the Discover tabs sit inline after the bar title, and the title is the current
 * tab's label — so its width changes on every switch. The title slot reserves the widest label's
 * width so the tabs hold one fixed position.
 */
@OptIn(ExperimentalTestApi::class)
class DiscoverInlineTabsTest {

    @Test
    fun inline_tabs_hold_position_while_the_title_changes() {
        Locale.setDefault(Locale.ENGLISH)
        runComposeUiTest {
            setContent {
                var tab by remember { mutableStateOf(DiscoverTab.Home) }
                YMusicTheme(dark = false) {
                    Box(Modifier.width(760.dp)) {
                        DesignAppBar(
                            title = stringResource(tab.label),
                            onMenu = {},
                            onSearch = {},
                            titleReserveWidthOf = DiscoverTab.entries.map { stringResource(it.label) },
                            inlineContent = { DiscoverTabRow(tab, inline = true) { tab = it } },
                        )
                    }
                }
            }

            val before = onNodeWithContentDescription("Trending music").fetchSemanticsNode().boundsInRoot.left
            // "Home" → "Recommended" is the biggest title-width jump the tabs can see.
            onNodeWithContentDescription("Recommended").performClick()
            waitForIdle()
            val after = onNodeWithContentDescription("Trending music").fetchSemanticsNode().boundsInRoot.left
            assertTrue(abs(before - after) < 0.5f, "Trending music tab moved: $before → $after")
        }
    }
}
