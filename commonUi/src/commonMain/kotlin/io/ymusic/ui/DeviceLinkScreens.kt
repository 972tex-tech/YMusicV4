package io.ymusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.dynamicengine.yt.ytLoginStatus
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_device_link_qr
import io.ymusic.ui.resources.device_link_done
import io.ymusic.ui.resources.device_link_empty_cookies
import io.ymusic.ui.resources.device_link_error_generic
import io.ymusic.ui.resources.device_link_error_network
import io.ymusic.ui.resources.device_link_error_no_lock
import io.ymusic.ui.resources.device_link_error_payload
import io.ymusic.ui.resources.device_link_error_timeout
import io.ymusic.ui.resources.device_link_intro
import io.ymusic.ui.resources.device_link_need_login
import io.ymusic.ui.resources.device_link_need_login_action
import io.ymusic.ui.resources.device_link_not_signed_in
import io.ymusic.ui.resources.device_link_open_camera
import io.ymusic.ui.resources.device_link_sas_hint
import io.ymusic.ui.resources.device_link_send
import io.ymusic.ui.resources.device_link_sending
import io.ymusic.ui.resources.device_link_title
import io.ymusic.ui.resources.device_link_unlock_hint
import io.ymusic.ui.resources.device_link_unlock_title
import io.ymusic.ui.resources.dialog_cancel
import io.ymusic.ui.resources.settings_link_devices
import io.ymusic.ui.resources.settings_link_devices_hint
import io.ymusic.ui.resources.signin_cookies_rejected
import io.ymusic.ui.resources.signin_or_paste
import io.ymusic.ui.resources.signin_paste_empty
import io.ymusic.ui.resources.signin_paste_hint
import io.ymusic.ui.resources.signin_paste_label
import io.ymusic.ui.resources.signin_paste_submit
import io.ymusic.ui.resources.signin_qr_hint
import io.ymusic.ui.resources.signin_qr_sas_hint
import io.ymusic.ui.resources.signin_qr_title
import io.ymusic.ui.resources.signin_qr_waiting
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DesktopCookieSignIn(
    graph: AppGraph,
    onComplete: () -> Unit,
    pasteFlow: Boolean,
    onOpenPaste: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val client = remember { newDeviceLinkHttpClient() }
    val log = remember(graph) { graph.logger?.fork("device-link") }
    val lock = remember { Mutex() }
    var offer by remember { mutableStateOf<DeviceLinkOffer?>(null) }
    var offerError by remember { mutableStateOf<String?>(null) }
    var paste by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    suspend fun finish(cookies: List<SessionCookie>) {
        lock.withLock {
            if (finished) return
            importing = true
            pasteError = null
            try {
                if (!cookies.looksSignedIn()) {
                    pasteError = getString(Res.string.signin_cookies_rejected)
                    return
                }
                graph.importSessionCookies!!(cookies)
                if (!graph.engines.ytLoginStatus()) {
                    pasteError = getString(Res.string.signin_cookies_rejected)
                    return
                }
                finished = true
                onComplete()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                log?.captureError(t, "cookie import failed")
                pasteError = getString(Res.string.device_link_error_generic)
            } finally {
                if (!finished) importing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        var minted: DeviceLinkOffer? = null
        try {
            minted = createDeviceLinkOffer(client)
            offer = minted
            finish(awaitDeviceLinkCookies(client, minted))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log?.log(Logger.Level.WARN, "device-link offer/poll failed", t)
            offerError = deviceLinkErrorText(t)
        } finally {
            minted?.let { deleteDeviceLinkToken(client, it.tokenId) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        val scroll = rememberScrollState()
        TrackScroll { scroll.canScrollBackward }
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = DesignSpacing.FormMaxWidth)
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight)
                    .padding(horizontal = DesignSpacing.MarginCompact, vertical = DesignSpacing.Space16),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16, Alignment.CenterVertically),
            ) {
                if (pasteFlow) {
                    Text(
                        stringResource(Res.string.signin_or_paste),
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CookieCopyGuide()
                    Text(
                        stringResource(Res.string.signin_paste_hint),
                        style = TextStyle(fontSize = 13.sp, color = DesignColors.InkMuted),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppTextField(
                        value = paste,
                        onValueChange = { paste = it; pasteError = null },
                        label = stringResource(Res.string.signin_paste_label),
                        singleLine = false,
                        isError = pasteError != null,
                        supportingText = pasteError,
                        modifier = Modifier.heightIn(min = 120.dp),
                    )
                    AccentButton(
                        text = stringResource(Res.string.signin_paste_submit),
                        enabled = paste.isNotBlank() && !importing,
                        onClick = {
                            scope.launch {
                                val cookies = parseCookiePaste(paste)
                                if (cookies.isEmpty()) {
                                    pasteError = getString(Res.string.signin_paste_empty)
                                } else {
                                    finish(cookies)
                                }
                            }
                        },
                    )
                } else {
                    Text(
                        stringResource(Res.string.signin_qr_title),
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(Res.string.signin_qr_hint),
                        style = TextStyle(fontSize = 13.sp, color = DesignColors.InkMuted),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val qrCd = stringResource(Res.string.cd_device_link_qr)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val payload = offer?.qrPayload
                        if (payload != null && !finished) {
                            DeviceLinkQrCode(
                                payload,
                                Modifier
                                    .size(220.dp)
                                    .padding(DesignSpacing.Space8)
                                    .semantics { contentDescription = qrCd },
                            )
                        } else if (offerError == null && !finished) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = DesignColors.Accent)
                        }
                    }
                    val sas = offer?.let { deviceLinkSas(it.key) }
                    if (sas != null && !finished) {
                        DeviceLinkSasBlock(sas, stringResource(Res.string.signin_qr_sas_hint))
                    }
                    when {
                        offerError != null -> Text(
                            offerError!!,
                            style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.error),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        pasteError != null -> Text(
                            pasteError!!,
                            style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.error),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        offer != null && !finished && !importing -> Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(
                                DesignSpacing.Space8,
                                Alignment.CenterHorizontally,
                            ),
                        ) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp),
                                color = DesignColors.Accent,
                                strokeWidth = 2.dp,
                            )
                            Text(
                                stringResource(Res.string.signin_qr_waiting),
                                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkMuted),
                            )
                        }
                    }
                    Text(
                        stringResource(Res.string.signin_or_paste),
                        style = TextStyle(fontSize = 13.sp, color = DesignColors.Accent),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clickable(onClick = onOpenPaste).padding(vertical = DesignSpacing.Space8),
                    )
                }
            }
        }
    }
}

@Composable
internal fun LinkDevicesRow(graph: AppGraph, signedIn: Boolean, onLogin: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.settings_link_devices),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
            )
            Text(
                stringResource(Res.string.settings_link_devices_hint),
                style = TextStyle(fontSize = 12.sp, color = DesignColors.InkSecondary),
                modifier = Modifier.padding(top = DesignSpacing.Space2),
            )
        }
    }
    if (open) {
        LinkDevicesDialog(
            graph,
            signedIn = signedIn,
            onDismiss = { open = false },
            onLogin = {
                open = false
                onLogin()
            },
        )
    }
}

@Composable
private fun LinkDevicesDialog(
    graph: AppGraph,
    signedIn: Boolean,
    onDismiss: () -> Unit,
    onLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val client = remember { newDeviceLinkHttpClient() }
    val log = remember(graph) { graph.logger?.fork("device-link") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    val scroll = rememberScrollState()

    suspend fun scanFromCamera() {
        error = null
        try {
            if (!signedIn || !graph.engines.ytLoginStatus()) return
            val raw = graph.scanQrCode!!() ?: return
            val parsed = parseDeviceLinkQr(raw)
            if (parsed == null) {
                error = getString(Res.string.device_link_error_payload)
                return
            }
            pending = parsed
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log?.captureError(t, "device-link scan failed")
            error = deviceLinkErrorText(t)
        }
    }

    suspend fun sendPending() {
        val parsed = pending ?: return
        error = null
        busy = true
        try {
            val unlock = graph.confirmDeviceOwner ?: error("no-lock")
            val unlocked = unlock(
                getString(Res.string.device_link_unlock_title),
                getString(Res.string.device_link_unlock_hint),
            )
            if (!unlocked) return
            if (!graph.engines.ytLoginStatus()) {
                error = getString(Res.string.device_link_not_signed_in)
                return
            }
            val cookies = graph.exportSessionCookies!!()
            if (!cookies.looksSignedIn()) {
                error = getString(Res.string.device_link_empty_cookies)
                return
            }
            postDeviceLinkCookies(client, parsed.first, parsed.second, cookies)
            done = true
            pending = null
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            log?.captureError(t, "device-link send failed")
            error = deviceLinkErrorText(t)
        } finally {
            if (!done) busy = false
        }
    }

    ElevatedBarDialog(
        title = stringResource(Res.string.device_link_title),
        onDismiss = onDismiss,
        canScrollBackward = scroll.canScrollBackward,
        canScrollForward = scroll.canScrollForward,
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dialog_cancel)) }
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = DesignSpacing.DialogRowHorizontal, vertical = DesignSpacing.Space16),
            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space16),
        ) {
            Text(
                stringResource(Res.string.device_link_intro),
                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkMuted),
            )
            when {
                done -> Text(
                    stringResource(Res.string.device_link_done),
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignColors.Ink),
                )
                busy -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = DesignColors.Accent, strokeWidth = 2.dp)
                    Text(
                        stringResource(Res.string.device_link_sending),
                        style = TextStyle(fontSize = 13.sp, color = DesignColors.InkMuted),
                    )
                }
                pending != null -> {
                    DeviceLinkSasBlock(
                        deviceLinkSas(pending!!.second),
                        stringResource(Res.string.device_link_sas_hint),
                    )
                    AccentButton(
                        text = stringResource(Res.string.device_link_send),
                        enabled = true,
                        onClick = { scope.launch { sendPending() } },
                    )
                }
                else -> {
                    if (!signedIn) DeviceLinkNeedLogin(onLogin)
                    AccentButton(
                        text = stringResource(Res.string.device_link_open_camera),
                        enabled = signedIn,
                        onClick = { scope.launch { scanFromCamera() } },
                    )
                }
            }
            error?.let {
                Text(it, style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.error))
            }
        }
    }
}

@Composable
private fun DeviceLinkNeedLogin(onLogin: () -> Unit) {
    val action = stringResource(Res.string.device_link_need_login_action)
    val line = stringResource(Res.string.device_link_need_login, action)
    val start = line.indexOf(action)
    val text = buildAnnotatedString {
        if (start < 0) {
            append(line)
            return@buildAnnotatedString
        }
        append(line.substring(0, start))
        withLink(
            LinkAnnotation.Clickable(
                tag = "login",
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = DesignColors.Accent,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
                linkInteractionListener = { onLogin() },
            ),
        ) {
            append(action)
        }
        append(line.substring(start + action.length))
    }
    Text(text, style = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.error))
}

@Composable
private fun AccentButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                if (enabled) DesignColors.Accent else DesignColors.Accent.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.OnInk),
        )
    }
}

@Composable
private fun DeviceLinkSasBlock(code: String, hint: String) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
    ) {
        Text(
            code,
            style = TextStyle(
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                color = DesignColors.Ink,
            ),
            textAlign = TextAlign.Center,
        )
        Text(
            hint,
            style = TextStyle(fontSize = 13.sp, color = DesignColors.InkMuted),
            textAlign = TextAlign.Center,
        )
    }
}

private suspend fun deviceLinkErrorText(t: Throwable): String = when (t.message) {
    "network" -> getString(Res.string.device_link_error_network)
    "timeout" -> getString(Res.string.device_link_error_timeout)
    "bad-payload" -> getString(Res.string.device_link_error_payload)
    "no-lock" -> getString(Res.string.device_link_error_no_lock)
    else -> getString(Res.string.device_link_error_generic)
}
