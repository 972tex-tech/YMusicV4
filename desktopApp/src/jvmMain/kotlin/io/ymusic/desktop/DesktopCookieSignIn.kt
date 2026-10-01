package io.ymusic.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ymusic.ui.ActionIcon
import io.ymusic.ui.AppTextField
import io.ymusic.ui.DesignColors
import io.ymusic.ui.DesignSpacing
import io.ymusic.ui.DeviceLinkOffer
import io.ymusic.ui.DeviceLinkQrCode
import io.ymusic.ui.SessionCookie
import io.ymusic.ui.awaitDeviceLinkCookies
import io.ymusic.ui.createDeviceLinkOffer
import io.ymusic.ui.deleteDeviceLinkToken
import io.ymusic.ui.deviceLinkSas
import io.ymusic.ui.looksSignedIn
import io.ymusic.ui.newDeviceLinkHttpClient
import io.ymusic.ui.parseCookiePaste
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.cd_back
import io.ymusic.ui.resources.cd_device_link_qr
import io.ymusic.ui.resources.device_link_error_generic
import io.ymusic.ui.resources.device_link_error_network
import io.ymusic.ui.resources.device_link_error_payload
import io.ymusic.ui.resources.device_link_error_timeout
import io.ymusic.ui.resources.signin_cookies_rejected
import io.ymusic.ui.resources.signin_or_paste
import io.ymusic.ui.resources.signin_paste_empty
import io.ymusic.ui.resources.signin_paste_label
import io.ymusic.ui.resources.signin_paste_submit
import io.ymusic.ui.resources.signin_qr_hint
import io.ymusic.ui.resources.signin_qr_sas_hint
import io.ymusic.ui.resources.signin_qr_title
import io.ymusic.ui.resources.signin_qr_waiting
import io.ymusic.ui.resources.signin_screen_title
import kmpcommon.log.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun DesktopCookieSignIn(
    importCookies: suspend (List<SessionCookie>) -> Unit,
    loginStatus: suspend () -> Boolean,
    logger: Logger?,
    onComplete: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val client = remember { newDeviceLinkHttpClient() }
    val log = remember(logger) { logger?.fork("device-link") }
    val lock = remember { Mutex() }
    var pasteFlow by remember { mutableStateOf(false) }
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
                importCookies(cookies)
                if (!loginStatus()) {
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

    Column(Modifier.fillMaxSize().background(DesignColors.Surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DesignSpacing.Space8, vertical = DesignSpacing.Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionIcon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(Res.string.cd_back),
                onClick = {
                    if (pasteFlow) pasteFlow = false else onBack()
                },
            )
            Text(
                stringResource(Res.string.signin_screen_title),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = DesignColors.Ink),
                modifier = Modifier.padding(start = DesignSpacing.Space8),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize().weight(1f)) {
            val viewportHeight = maxHeight
            val scroll = rememberScrollState()
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
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DesignColors.Ink,
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CookieCopyGuide()
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
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DesignColors.Ink,
                            ),
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
                        Box(
                            Modifier.fillMaxWidth().height(220.dp),
                            contentAlignment = Alignment.Center,
                        ) {
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
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
                        ) {
                            Text(
                                sas ?: "000\u00a0000",
                                style = TextStyle(
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 2.sp,
                                    color = if (sas != null) DesignColors.Ink else Color.Transparent,
                                ),
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                stringResource(Res.string.signin_qr_sas_hint),
                                style = TextStyle(fontSize = 13.sp, color = DesignColors.InkMuted),
                                textAlign = TextAlign.Center,
                            )
                        }
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
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
                                !finished && !importing -> Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(DesignSpacing.Space8),
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
                        }
                        Text(
                            stringResource(Res.string.signin_or_paste),
                            style = TextStyle(fontSize = 13.sp, color = DesignColors.Accent),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clickable { pasteFlow = true }
                                .padding(vertical = DesignSpacing.Space8),
                        )
                    }
                }
            }
        }
    }
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

private suspend fun deviceLinkErrorText(t: Throwable): String = when (t.message) {
    "network" -> getString(Res.string.device_link_error_network)
    "timeout" -> getString(Res.string.device_link_error_timeout)
    "bad-payload" -> getString(Res.string.device_link_error_payload)
    else -> getString(Res.string.device_link_error_generic)
}
