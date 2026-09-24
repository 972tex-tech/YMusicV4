package io.ymusic.android

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
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import io.ymusic.ui.App
import io.ymusic.ui.AppGraph
import io.ymusic.ui.AppSection
import io.ymusic.ui.FeedbackFile
import kmpcommon.uri.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android UI shell: boots the shared :commonUi [App] over the application-scoped [AppGraph]
 * (see [YMusicApplication] — playback outlives this Activity in the [PlaybackService]). The
 * Activity contributes only what needs one: the SAF folder pick and the notification permission.
 */
class MainActivity : ComponentActivity() {
    /** A section requested by a platform surface (download notification tap), consumed by the
     *  composition once the graph is up — survives arriving before the app finishes booting. */
    private val pendingSection = mutableStateOf<AppSection?>(null)

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

    // Same deferred bridge for the feedback attachment pick (any file type, multi-select).
    private var pendingFilePick: CompletableDeferred<List<android.net.Uri>>? = null
    private val openFiles =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            pendingFilePick?.complete(uris)
            pendingFilePick = null
        }

    /** Launches the system file picker and reads each pick into a [FeedbackFile]; an over-limit
     *  file comes back name-only (null bytes) so the form can refuse it with its name. */
    private val pickFeedbackFiles: suspend () -> List<FeedbackFile> = {
        pendingFilePick?.complete(emptyList()) // a prior pick still open → abandon it
        val deferred = CompletableDeferred<List<android.net.Uri>>()
        pendingFilePick = deferred
        withContext(Dispatchers.Main) { openFiles.launch("*/*") }
        val uris = deferred.await()
        withContext(Dispatchers.IO) {
            // Per-file isolation: one unreadable pick (an offline cloud-provider entry, a revoked
            // grant) must not abort the whole batch — the rest still attach.
            uris.mapNotNull { uri ->
                try {
                    readFeedbackFile(uri)
                } catch (e: Exception) {
                    (application as YMusicApplication).rootLogger.fork("feedback")
                        .log(kmpcommon.log.Logger.Level.WARN, "attachment read failed: $uri", e)
                    null
                }
            }
        }
    }

    private fun readFeedbackFile(uri: android.net.Uri): FeedbackFile {
        var name: String? = null
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) name = c.getString(nameIdx)
                val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
        val filename = name ?: uri.lastPathSegment ?: "attachment"
        if (size > FeedbackFile.MAX_BYTES) return FeedbackFile(filename, bytes = null, sizeBytes = size)
        // Size can be unknown (-1): read capped, and past the cap it degrades to name-only the same way.
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                out.write(chunk, 0, n)
                total += n
                if (total > FeedbackFile.MAX_BYTES) return FeedbackFile(filename, bytes = null, sizeBytes = total)
            }
            out.toByteArray()
        } ?: error("unreadable attachment: $filename")
        return FeedbackFile(filename, bytes, contentType = contentResolver.getType(uri))
    }

    override fun onDestroy() {
        super.onDestroy()
        val app = application as YMusicApplication
        if (app.folderPicker === pickFolder) app.folderPicker = null
        pendingPick?.complete(null)
        pendingPick = null
        if (app.feedbackFilePicker === pickFeedbackFiles) app.feedbackFilePicker = null
        pendingFilePick?.complete(emptyList())
        pendingFilePick = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is light-only (commonUi LightScheme paints white behind the bars), so the bar
        // icon color must not follow the SYSTEM dark-mode setting — that yields white-on-white.
        val light = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = light, navigationBarStyle = light)
        // Compose paints its own surface; a window background under it is 1× overdraw on every pixel.
        window.setBackgroundDrawable(null)
        // Diagnosis-only instrumentation: the watchdog probes the main looper/choreographer/frame
        // clock every 3s and the composition probe forces a frame request every 500ms — never in a
        // release build. BuildConfig.DEBUG is a compile-time constant, so R8 strips these branches
        // (and the watchdog classes with them) from the release dex entirely.
        if (BuildConfig.DEBUG) {
            FreezeWatchdog.start()
            lifecycle.addObserver(
                androidx.lifecycle.LifecycleEventObserver { _, event ->
                    android.util.Log.i("FreezeWatchdog", "lifecycle $event")
                },
            )
        }
        (application as YMusicApplication).folderPicker = pickFolder
        (application as YMusicApplication).feedbackFilePicker = pickFeedbackFiles
        sectionFor(intent)?.let { pendingSection.value = it }
        setContent {
            if (BuildConfig.DEBUG) CompositionClockProbe()
            var graph by remember { mutableStateOf<AppGraph?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                try {
                    graph = (application as YMusicApplication).graphAsync().await()
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c // the effect was disposed — not a boot failure
                } catch (t: Throwable) {
                    (application as YMusicApplication).rootLogger.fork("boot").captureError(t)
                    error = t.message ?: t.toString()
                }
            }
            when {
                error != null -> Booting { Text("Startup failed: $error", color = MaterialTheme.colorScheme.error) }
                graph == null -> Booting { CircularProgressIndicator() }
                else -> {
                    val section = rememberSaveable { mutableStateOf(AppSection.Discover) }
                    LaunchedEffect(pendingSection.value) {
                        pendingSection.value?.let {
                            section.value = it
                            pendingSection.value = null
                        }
                    }
                    App(graph!!, section)
                }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sectionFor(intent)?.let { pendingSection.value = it }
    }

    private fun sectionFor(intent: Intent?): AppSection? = when (intent?.action) {
        ACTION_OPEN_DOWNLOADS -> AppSection.Downloads
        else -> null
    }

    companion object {
        /** Launch intent action landing on the Download manager (the download notifications). */
        const val ACTION_OPEN_DOWNLOADS = "io.ymusic.android.action.OPEN_DOWNLOADS"
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
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) { content() }
}
