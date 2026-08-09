package ymusic.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        requestNotificationPermission()
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
        }
    }

    /** The playback notification needs POST_NOTIFICATIONS on 13+; ask up front, play works either way. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }
}

@androidx.compose.runtime.Composable
private fun Booting(content: @androidx.compose.runtime.Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
