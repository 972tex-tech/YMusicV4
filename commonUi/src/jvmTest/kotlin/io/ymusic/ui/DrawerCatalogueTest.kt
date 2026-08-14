package io.ymusic.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import java.util.Locale
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DrawerCatalogueTest {
    @Test
    fun catalogue_item_follows_showCatalogue() {
        Locale.setDefault(Locale.ENGLISH)
        runComposeUiTest {
            setContent {
                YMusicTheme {
                    AppDrawer(AppSection.Discover, appVersion = null, onSelect = {}, showCatalogue = true)
                }
            }
            onNodeWithText("UI catalogue").assertExists()
        }
        runComposeUiTest {
            setContent {
                YMusicTheme {
                    AppDrawer(AppSection.Discover, appVersion = null, onSelect = {}, showCatalogue = false)
                }
            }
            onNodeWithText("UI catalogue").assertDoesNotExist()
            onNodeWithText("Settings").assertExists()
        }
    }
}
