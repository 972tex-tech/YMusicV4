package ymusic.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import kmpcommon.uri.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ymusic.ui.App
import ymusic.ui.AppGraph

/**
 * The Android UI shell: boots the shared :commonUi [App] over the application-scoped [AppGraph]
 * (see [YMusicApplication] — playback outlives this Activity in the [PlaybackService]). The
 * Activity contributes only what needs one: the SAF folder pick and the notification permission.
 */
class MainActivity : ComponentActivity() {
    // Notifications are MANDATORY: playback lives in a foreground service whose notification is
    // its lifeline. While disabled, a non-dismissible dialog sits over the app with granting as
    // the only way out — [notificationsEnabled] is re-checked on every resume, so returning from
    // the settings screen unblocks without a restart.
    private val notificationsEnabled = mutableStateOf(true)
    private var askedForNotifications = false
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            askedForNotifications = true
            notificationsEnabled.value = granted
            // Second "Don't allow" is permanent on 13+ — the settings screen is the only path left.
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                openNotificationSettings()
            }
        }

    // Bridges the callback-based SAF folder pick to the shell's suspend ScanAction: launch() fires the
    // system ACTION_OPEN_DOCUMENT_TREE, the result callback completes the pending deferred.
    private var pendingPick: CompletableDeferred<Uri?>? = null
    private val openFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                // Persist read access so a rescan (and next launch) can reach the folder without re-picking.
                contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            pendingPick?.complete(treeUri?.let { Uri.parse(it.toString()) })
            pendingPick = null
        }

    /** Launches the SAF folder picker and suspends until the user picks a tree (or cancels → null). */
    private val pickFolder: suspend () -> Uri? = {
        pendingPick?.complete(null) // a prior pick still open → abandon it
        val deferred = CompletableDeferred<Uri?>()
        pendingPick = deferred
        withContext(Dispatchers.Main) { openFolder.launch(null) }
        deferred.await()
    }

    override fun onDestroy() {
        super.onDestroy()
        val app = application as YMusicApplication
        if (app.folderPicker === pickFolder) app.folderPicker = null
        pendingPick?.complete(null)
        pendingPick = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is light-only (commonUi LightScheme paints white behind the bars), so the bar
        // icon color must not follow the SYSTEM dark-mode setting — that yields white-on-white.
        val light = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = light, navigationBarStyle = light)
        FreezeWatchdog.start()
        lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                android.util.Log.i("FreezeWatchdog", "lifecycle $event")
            },
        )
        (application as YMusicApplication).folderPicker = pickFolder
        setContent {
            CompositionClockProbe()
            var graph by remember { mutableStateOf<AppGraph?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                try {
                    graph = (application as YMusicApplication).graphAsync().await()
                } catch (t: Throwable) {
                    error = t.message ?: t.toString()
                }
            }
            when {
                error != null -> Booting { Text("Startup failed: $error", color = MaterialTheme.colorScheme.error) }
                graph == null -> Booting { CircularProgressIndicator() }
                else -> App(graph!!)
            }
            val notificationsOk = notificationsEnabled.value
            LaunchedEffect(notificationsOk) {
                // First launch on 13+: fire the system dialog straight away; the dialog's button
                // covers retries and the pre-13 app-setting case (which has no dialog to show).
                if (!notificationsOk && Build.VERSION.SDK_INT >= 33 && !askedForNotifications) {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (!notificationsOk) {
                NotificationRequiredDialog(onAllow = ::requestNotificationAccess)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers 13+ permission AND the pre-13 app-level notification toggle.
        notificationsEnabled.value = NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= 33 &&
            (!askedForNotifications || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
        }
        startActivity(intent)
    }
}

/** Notifications are mandatory (the playback service's notification is its lifeline): this sits
 *  over whatever is showing and offers exactly one way out — granting. Not dismissible. */
@androidx.compose.runtime.Composable
private fun NotificationRequiredDialog(onAllow: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        text = { Text(stringResource(R.string.notifications_required)) },
        confirmButton = {
            TextButton(onClick = onAllow) { Text(stringResource(R.string.notifications_allow)) }
        },
    )
}

@androidx.compose.runtime.Composable
private fun Booting(content: @androidx.compose.runtime.Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
