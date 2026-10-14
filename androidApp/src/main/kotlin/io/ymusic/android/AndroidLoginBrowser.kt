package io.ymusic.android

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.ymusic.ui.LoginWebViewState

/**
 * The Android sign-in WebView (the shell's [io.ymusic.ui.AppGraph.loginBrowser]). Cookies land in
 * the WebView [CookieManager], which [WebViewCookieJar] wraps for engine HTTP.
 * Reports title / URL / progress into [state]. A null [userAgent] keeps the mobile default, correct
 * for the m.youtube.com sign-in (matching the legacy WebViewLoginFragment); the captcha browser
 * passes the engine scrape's UA so the cleared challenge matches the identity it scrapes with.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AndroidLoginBrowser(
    url: String,
    userAgent: String?,
    state: LoginWebViewState,
    onPageChanged: () -> Unit,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            WebView(context).apply {
                cookies.setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                if (userAgent != null) settings.userAgentString = userAgent
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                        state.currentUrl = u
                        state.canGoBack = view.canGoBack()
                        onPageChanged()
                    }

                    override fun onPageFinished(view: WebView, u: String?) {
                        state.currentUrl = u
                        state.canGoBack = view.canGoBack()
                        // Persist the WebView cookie store so the engine jar (same CookieManager)
                        // sees the sign-in cookies immediately on the next gate poll.
                        cookies.flush()
                        onPageChanged()
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        state.progress = newProgress / 100f
                    }

                    override fun onReceivedTitle(view: WebView, title: String?) {
                        state.title = title
                    }
                }
                state.back = { if (canGoBack()) goBack() }
                loadUrl(url)
            }
        },
    )
}
