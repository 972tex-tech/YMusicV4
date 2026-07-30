package ymusic.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import ymusic.ui.LoginWebViewState

/**
 * The desktop sign-in WebView (the shell's [ymusic.ui.AppGraph.loginBrowser]) — a WINDOWED JCEF
 * browser (not the player's OSR surface, which has no keyboard input) hosted in a [SwingPanel]. It's
 * a full-area view under the Compose chrome, so the usual heavyweight/lightweight overlap doesn't
 * arise. Cookies go to JCEF's global store — the same one [JcefCookieJar] gives the engine — so the
 * sign-in cookies reach the login gate. Its own [org.cef.CefClient] keeps its handlers off the
 * player's shared client.
 */
@Composable
fun DesktopLoginBrowser(
    url: String,
    state: LoginWebViewState,
    onPageChanged: () -> Unit,
    modifier: Modifier,
) {
    val holder = remember {
        val client = CefApp.getInstance().createClient()
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onTitleChange(browser: CefBrowser?, title: String?) {
                state.title = title
            }

            override fun onAddressChange(browser: CefBrowser?, frame: CefFrame?, u: String?) {
                state.currentUrl = u
                if (browser != null) state.canGoBack = browser.canGoBack()
                onPageChanged()
            }
        })
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            // JCEF reports loading state, not a 0-100 percent — a coarse "loading vs done" bar is enough.
            override fun onLoadingStateChange(
                browser: CefBrowser?,
                isLoading: Boolean,
                canGoBack: Boolean,
                canGoForward: Boolean,
            ) {
                state.canGoBack = canGoBack
                state.progress = if (isLoading) 0.15f else 1f
                if (!isLoading) onPageChanged()
            }
        })
        val browser = client.createBrowser(url, false, false)
        state.back = { if (browser.canGoBack()) browser.goBack() }
        LoginBrowserHolder(client, browser)
    }
    SwingPanel(
        background = Color.Transparent,
        factory = { holder.browser.uiComponent },
        modifier = modifier,
    )
    DisposableEffect(Unit) {
        onDispose {
            state.back = null
            holder.browser.close(true)
            holder.client.dispose()
        }
    }
}

private class LoginBrowserHolder(val client: org.cef.CefClient, val browser: CefBrowser)
