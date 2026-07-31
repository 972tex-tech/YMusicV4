package ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ymusic.dynamicengine.yt.YtAccountInfo
import ymusic.dynamicengine.yt.ytAccountInfo
import ymusic.dynamicengine.yt.ytLoginStatus
import ymusic.dynamicengine.yt.ytLoginUrl
import ymusic.dynamicengine.yt.ytLogout
import ymusic.dynamicengine.yt.ytRefreshSession
import ymusic.ui.resources.Res
import ymusic.ui.resources.cd_back
import ymusic.ui.resources.signin_button
import ymusic.ui.resources.signin_enables
import ymusic.ui.resources.signin_feature_history
import ymusic.ui.resources.signin_feature_home
import ymusic.ui.resources.signin_feature_like
import ymusic.ui.resources.signin_feature_more
import ymusic.ui.resources.signin_feature_playlists
import ymusic.ui.resources.signin_feature_subscribe
import ymusic.ui.resources.signin_feature_subscriptions
import ymusic.ui.resources.signin_preface_subtitle
import ymusic.ui.resources.signin_preface_title
import ymusic.ui.resources.signin_screen_title
import ymusic.ui.resources.signin_unavailable

/**
 * The observable state of the login browser — the shell's [LoginBrowser] writes it, the login chrome
 * reads it. A plain snapshot-state holder (no engine coupling), so a shell WebView only needs to
 * report page title / current URL / load progress and expose a back action.
 */
class LoginWebViewState {
    /** The current page's `<title>`; null before the first page paints. */
    var title: String? by mutableStateOf(null)

    /** The current URL, shown read-only in the chrome (not editable — this is a sign-in flow). */
    var currentUrl: String? by mutableStateOf(null)

    /** Load progress 0f‥1f (1 = finished). The chrome shows the bar only mid-load. */
    var progress: Float by mutableStateOf(0f)

    /** Whether the WebView has back history — drives the chrome/back-gesture behaviour. */
    var canGoBack: Boolean by mutableStateOf(false)

    /** Set by the shell WebView so the chrome/back gesture can walk the page's own history. */
    var back: (() -> Unit)? = null

    /** Set by shells that can open a DevTools window (desktop JCEF); null hides the chrome button.
     *  Snapshot state so the chrome shows the button once the shell wires it. */
    var openDevTools: (() -> Unit)? by mutableStateOf(null)

    fun goBack() {
        back?.invoke()
    }
}

/**
 * A platform browser WebView, injected by the platform shell (like [AppGraph.playerSurface]) so the
 * pure shared UI never depends on a WebView stack. It loads [url], reports title / URL / progress
 * into [state], and calls [onPageChanged] after each navigation. It MUST use the platform's default
 * cookie store — the same jar the engine reads (`WebViewCookieJar` / `JcefCookieJar` /
 * `IosWebViewCookieJar`) — so the sign-in cookies land where the scraper's login gate sees them.
 */
typealias LoginBrowser = @Composable (
    url: String,
    state: LoginWebViewState,
    onPageChanged: () -> Unit,
    modifier: Modifier,
) -> Unit

private const val LOGIN_POLL_MS = 1_000L

/**
 * Signed-in state for the app, derived entirely from the engine's cookie gate. Holds no cookies
 * itself: the auth cookies live in the shared platform jar (the engine reads them), and this only
 * caches the display card plus a [loginEpoch] the feeds watch to reload on a sign-in/out.
 */
internal class AccountController(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _account = MutableStateFlow<YtAccountInfo?>(null)
    val account: StateFlow<YtAccountInfo?> = _account.asStateFlow()

    /** Bumped on every sign-in/out so the feed caches clear and reload (mirrors [AppGraph.localeEpoch]). */
    val loginEpoch = MutableStateFlow(0)

    init {
        // The auth cookies persist in the platform jar across launches, so probe on start: an already
        // signed-in user comes back signed in without touching the login screen.
        scope.launch {
            val ok = runCatching { graph.engines.ytLoginStatus() }.getOrDefault(false)
            _signedIn.value = ok
            if (ok) {
                val acct = runCatching { graph.engines.ytAccountInfo() }.getOrNull()
                if (acct != null && !acct.signedIn) {
                    // The engine found no account behind the cookies (a revoked jar — remote
                    // logout). Run the full sign-out so the state, session and feeds all reset
                    // instead of showing a phantom profile. A fetch FAILURE is not this: acct
                    // stays null and the cookie gate's answer stands.
                    signOut()
                } else {
                    _account.value = acct
                }
            }
        }
    }

    /** After the login screen's cookie gate passed: pull the account card, reload every feed, and
     *  stale the player's page so the next track re-fetches /embed authenticated. */
    fun onLoginComplete() {
        scope.launch {
            _signedIn.value = true
            // Re-scrape the session under the new cookies so authenticated calls carry the account's
            // SESSION_INDEX before the account fetch and feed reload below.
            runCatching { graph.engines.ytRefreshSession() }
            // Force a fresh fetch (and re-cache) on sign-in; the startup probe below uses the cache.
            _account.value = runCatching { graph.engines.ytAccountInfo(refresh = true) }.getOrNull()
            loginEpoch.value += 1
            graph.player.refreshOnNext()
            graph.requestRefresh()
        }
    }

    fun signOut() {
        scope.launch {
            runCatching { graph.engines.ytLogout() }
            // Re-scrape anonymous so the account identifiers drop out of subsequent requests.
            runCatching { graph.engines.ytRefreshSession() }
            _signedIn.value = false
            _account.value = null
            loginEpoch.value += 1
            graph.player.refreshOnNext()
            graph.requestRefresh()
        }
    }
}

/**
 * The full-screen sign-in browser: a browser-like chrome (back · page title · read-only URL ·
 * progress) over the shell's WebView, polling the engine's COOKIE-ONLY gate. The moment the essential
 * cookies land it calls [onComplete] — never a network call mid-flow, which YouTube answers by
 * revoking a half-set jar.
 */
@Composable
internal fun YtLoginScreen(
    graph: AppGraph,
    state: LoginWebViewState,
    onBack: () -> Unit,
    onComplete: () -> Unit,
) {
    var entryUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        entryUrl = runCatching { graph.engines.ytLoginUrl() }.getOrNull()
    }

    val browser = graph.loginBrowser
    // Poll the cookie gate while the user signs in. Cookie-only, so polling costs nothing and never
    // disturbs the half-set jar. Stops itself the instant it flips (onComplete pops this screen).
    LaunchedEffect(browser) {
        if (browser == null) return@LaunchedEffect
        var done = false
        while (isActive && !done) {
            delay(LOGIN_POLL_MS)
            if (runCatching { graph.engines.ytLoginStatus() }.getOrDefault(false)) {
                done = true
                onComplete()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(DesignColors.Surface)) {
        LoginChrome(state, onBack)
        val url = entryUrl
        when {
            browser == null -> CenteredBox {
                Text(stringResource(Res.string.signin_unavailable), color = DesignColors.InkMuted)
            }
            url == null -> CenteredBox { CircularProgressIndicator(color = DesignColors.Accent) }
            else -> browser(url, state, {}, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun LoginChrome(state: LoginWebViewState, onBack: () -> Unit) = ElevatedBarColumn {
    AppBarRow {
        ActionIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.cd_back), onClick = onBack)
        Column(Modifier.weight(1f)) {
            Text(
                state.title?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.signin_screen_title),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            state.currentUrl?.takeIf { it.isNotBlank() }?.let { u ->
                Text(
                    u,
                    style = TextStyle(fontSize = 11.5.sp, color = DesignColors.InkFaint),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        state.openDevTools?.let { open ->
            ActionIcon(Icons.Default.Code, "DevTools", onClick = open)
        }
    }
    // The load bar shows only mid-navigation; a finished (1f) or idle (0f) page hides it.
    if (state.progress > 0.001f && state.progress < 0.999f) {
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = DesignColors.Accent,
            trackColor = Color.Transparent,
        )
    }
}

/** The signed-out Discover "Account" tab — the "Sign in with YouTube" preface (design mirrors the
 *  legacy LoginIntroduceFragment). */
@Composable
internal fun LoginPreface(onSignIn: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(56.dp).background(Color(0xFFFF0000), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Column {
                Text(
                    stringResource(Res.string.signin_preface_title),
                    style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
                )
                Text(
                    stringResource(Res.string.signin_preface_subtitle),
                    style = TextStyle(fontSize = 14.sp, color = DesignColors.InkMuted),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = DesignColors.Divider)
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(Res.string.signin_enables),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.InkMuted),
        )
        Spacer(Modifier.height(12.dp))
        for (feature in LOGIN_FEATURES) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(10.dp).background(DesignColors.Accent, CircleShape))
                Text(stringResource(feature), style = TextStyle(fontSize = 15.sp, color = DesignColors.Ink))
            }
        }
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(DesignColors.Accent, RoundedCornerShape(8.dp))
                .clickable(onClick = onSignIn)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(Res.string.signin_button).uppercase(),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.OnInk),
            )
        }
    }
}

private val LOGIN_FEATURES = listOf(
    Res.string.signin_feature_home,
    Res.string.signin_feature_subscriptions,
    Res.string.signin_feature_playlists,
    Res.string.signin_feature_history,
    Res.string.signin_feature_subscribe,
    Res.string.signin_feature_like,
    Res.string.signin_feature_more,
)

// The signed-in Account tab is AccountLibraryScreen (AccountLibrary.kt) — the account card plus
// the engine's library feed.
