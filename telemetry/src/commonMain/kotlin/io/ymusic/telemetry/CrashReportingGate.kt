package io.ymusic.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Consent gating WITHOUT ever deinitializing: [init] runs once — on the FIRST consent value, so
 * the options it applies (auto session tracking) read the stored answer — and the crash handlers
 * it installs (uncaught-exception, ANR, NDK signals) are never torn down; Sentry.close() would
 * uninstall and reinstall them on every toggle, and that handler chain is not worth disturbing.
 * From then on consent controls what LEAVES the process: the beforeSend hooks in
 * [applyYMusicTelemetry] drop events and logs while [consent] says no.
 *
 * The switch is live both ways, no restart (sessions excepted — that option is per launch).
 * [crashReportingAllowed] is false until the first consent value arrives, so nothing races out
 * during startup.
 */
fun bindCrashReporting(scope: CoroutineScope, consent: Flow<Boolean>, init: () -> Unit) {
    var initialized = false
    scope.launch {
        consent.distinctUntilChanged().collect { allowed ->
            crashReportingAllowed = allowed
            if (!initialized) {
                initialized = true
                init()
            }
        }
    }
}

/** Mirror of the consent switch for [applyYMusicTelemetry]'s beforeSend hooks. False until consent. */
@Volatile
internal var crashReportingAllowed: Boolean = false
