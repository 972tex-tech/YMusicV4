package io.ymusic.ui

import kmphttp.CookieBuilder
import kmphttp.CookieJar
import kmphttp.KmpHttpClient
import kmphttp.MediaTypeX.toMediaType
import kmphttp.RequestBodyX.toRequestBody
import kmphttp.RequestBuilder
import kmphttp.create
import kmphttp.deleteWithoutBody
import kmphttp.saveFromResponse
import kmphttp.toHttpUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.use
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** One YouTube/Google cookie moved between devices. [domain] is a suffix (no leading dot). */
data class SessionCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val secure: Boolean = true,
    val httpOnly: Boolean = false,
    val expiresAt: Long? = null,
)

/** Desktop-minted inbox: a webhook.site URL plus the AES-equivalent key encoded in the QR. */
class DeviceLinkOffer(
    val webhookUrl: String,
    val tokenId: String,
    val key: ByteArray,
) {
    val qrPayload: String
        get() = "$QR_PREFIX$webhookUrl|$QR_SEP${key.toByteString().base64Url()}"
}

internal fun parseDeviceLinkQr(raw: String): Pair<String, ByteArray>? {
    val text = raw.trim()
    if (!text.startsWith(QR_PREFIX)) return null
    val rest = text.removePrefix(QR_PREFIX)
    val bar = rest.indexOf("|$QR_SEP")
    if (bar <= 0) return null
    val url = rest.substring(0, bar)
    if (!WEBHOOK_INBOX.matches(url)) return null
    val key = rest.substring(bar + 1 + QR_SEP.length).decodeBase64Url() ?: return null
    if (key.size != KEY_LEN) return null
    return url to key
}

suspend fun createDeviceLinkOffer(client: KmpHttpClient): DeviceLinkOffer {
    val json = """{"expiry":$TOKEN_EXPIRY_SEC,"request_limit":1}"""
    val body = client.execute(
        RequestBuilder()
            .url(WEBHOOK_TOKEN)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build(),
    ).use { response ->
        if (!response.isSuccessful) error("network")
        response.body.string()
    }
    val uuid = UUID_FIELD.find(body)?.groupValues?.get(1) ?: error("network")
    val url = "$WEBHOOK_ORIGIN/$uuid"
    check(WEBHOOK_INBOX.matches(url))
    return DeviceLinkOffer(url, uuid, deviceLinkRandomBytes(KEY_LEN))
}

/** Drops the webhook.site inbox. Failures are ignored — the token also expires at [TOKEN_EXPIRY_SEC]. */
suspend fun deleteDeviceLinkToken(client: KmpHttpClient, tokenId: String) {
    try {
        client.execute(
            RequestBuilder()
                .url("$WEBHOOK_ORIGIN/token/$tokenId")
                .header("User-Agent", USER_AGENT)
                .deleteWithoutBody()
                .build(),
        ).use { }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        // Fire-and-forget cleanup of the one-shot inbox.
    }
}

fun deviceLinkSas(key: ByteArray): String {
    require(key.size == KEY_LEN)
    val mac = hmac(key, SAS_LABEL)
    var n = 0L
    for (i in 0 until 4) n = (n shl 8) or (mac[i].toLong() and 0xffL)
    val digits = (n % 1_000_000L).toString().padStart(6, '0')
    return digits.substring(0, 3) + "\u00a0" + digits.substring(3)
}

internal suspend fun pollDeviceLinkCookies(
    client: KmpHttpClient,
    offer: DeviceLinkOffer,
): List<SessionCookie>? {
    val url = "$WEBHOOK_ORIGIN/token/${offer.tokenId}/request/latest/raw"
    val response = client.execute(
        RequestBuilder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build(),
    )
    val raw = response.use {
        if (it.code == 404) return null
        if (!it.isSuccessful) error("network")
        it.body.string().trim().trim('"')
    }
    deleteDeviceLinkToken(client, offer.tokenId)
    val blob = raw.decodeBase64Url() ?: error("bad-payload")
    val plain = deviceLinkDecrypt(offer.key, blob)
    return decodeCookies(plain).ifEmpty { error("bad-payload") }
}

internal suspend fun postDeviceLinkCookies(
    client: KmpHttpClient,
    webhookUrl: String,
    key: ByteArray,
    cookies: List<SessionCookie>,
) {
    check(WEBHOOK_INBOX.matches(webhookUrl))
    val blob = deviceLinkEncrypt(key, encodeCookies(cookies))
    val body = blob.toByteString().base64Url()
    client.execute(
        RequestBuilder()
            .url(webhookUrl)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "text/plain")
            .post(body.toRequestBody(null))
            .build(),
    ).use { response ->
        if (!response.isSuccessful) error("network")
    }
}

@OptIn(ExperimentalTime::class)
suspend fun CookieJar.importSessionCookies(cookies: List<SessionCookie>) {
    val now = Clock.System.now().toEpochMilliseconds()
    val fallbackExpiry = now + PERSIST_MS
    val built = cookies.map { c ->
        val domain = c.domain.removePrefix(".").ifBlank { "youtube.com" }
        val builder = CookieBuilder()
            .name(c.name)
            .value(c.value)
            .domain(domain)
            .path(c.path.ifBlank { "/" })
            .expiresAt(c.expiresAt?.takeIf { it > now } ?: fallbackExpiry)
        if (c.secure) builder.secure()
        if (c.httpOnly) builder.httpOnly()
        builder.build()
    }
    withContext(Dispatchers.IO) {
        saveFromResponse("https://www.youtube.com/".toHttpUrl(), built)
    }
}

fun parseCookiePaste(text: String): List<SessionCookie> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    val json = parseJsonCookiePaste(trimmed)
    if (json.isNotEmpty()) return json
    val lines = trimmed.lineSequence().map { it.trimEnd() }.toList()
    val header = lines.firstOrNull { it.isNotBlank() && !it.startsWith('#') }.orEmpty()
    if (header.contains('\t') && header.contains("Name", ignoreCase = true) &&
        header.contains("Value", ignoreCase = true)
    ) {
        return parseChromeTsv(lines)
    }
    if (header.contains('\t')) {
        val netscape = parseNetscape(lines)
        if (netscape.isNotEmpty()) return netscape
        val tsv = parseChromeTsv(lines)
        if (tsv.isNotEmpty()) return tsv
    }
    val payload = cookiePayloadFromPaste(trimmed)
    if (payload != null) {
        val extracted = parseCookieHeader(payload)
        if (extracted.size >= 2 || looksLikeCurl(trimmed)) return extracted
    }
    val headerCookies = parseCookieHeader(trimmed)
    if (headerCookies.size >= 2) return headerCookies
    val named = parseNameValueLines(lines)
    return named.ifEmpty { headerCookies }
}

internal expect fun deviceLinkRandomBytes(n: Int): ByteArray

internal fun deviceLinkEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
    require(key.size == KEY_LEN)
    val iv = deviceLinkRandomBytes(IV_LEN)
    val encKey = hmac(key, ENC_LABEL)
    val macKey = hmac(key, MAC_LABEL)
    val ciphertext = xorKeystream(encKey, iv, plaintext)
    val mac = hmac(macKey, iv + ciphertext)
    return iv + ciphertext + mac
}

internal fun deviceLinkDecrypt(key: ByteArray, blob: ByteArray): ByteArray {
    require(key.size == KEY_LEN)
    if (blob.size < IV_LEN + MAC_LEN) error("bad-payload")
    val iv = blob.copyOfRange(0, IV_LEN)
    val mac = blob.copyOfRange(blob.size - MAC_LEN, blob.size)
    val ciphertext = blob.copyOfRange(IV_LEN, blob.size - MAC_LEN)
    val encKey = hmac(key, ENC_LABEL)
    val macKey = hmac(key, MAC_LABEL)
    val expect = hmac(macKey, iv + ciphertext)
    if (!macEquals(mac, expect)) error("bad-payload")
    return xorKeystream(encKey, iv, ciphertext)
}

private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
    data.toByteString().hmacSha256(key.toByteString()).toByteArray()

private fun hmac(key: ByteArray, label: String): ByteArray = hmac(key, label.encodeToByteArray())

private fun xorKeystream(encKey: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(data.size)
    var offset = 0
    var block = 0
    while (offset < data.size) {
        val ks = Buffer().apply {
            write(iv)
            writeInt(block)
        }.readByteString().hmacSha256(encKey.toByteString()).toByteArray()
        val n = minOf(ks.size, data.size - offset)
        for (i in 0 until n) out[offset + i] = (data[offset + i].toInt() xor ks[i].toInt()).toByte()
        offset += n
        block++
    }
    return out
}

private fun macEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var d = 0
    for (i in a.indices) d = d or (a[i].toInt() xor b[i].toInt())
    return d == 0
}

private const val FIELD = '\u001f'

private fun encodeCookies(cookies: List<SessionCookie>): ByteArray {
    val sb = StringBuilder()
    for (c in cookies) {
        sb.append(c.name).append(FIELD)
            .append(c.value).append(FIELD)
            .append(c.domain.removePrefix(".")).append(FIELD)
            .append(c.path.ifBlank { "/" }).append(FIELD)
            .append(if (c.secure) '1' else '0').append(FIELD)
            .append(if (c.httpOnly) '1' else '0').append(FIELD)
            .append(c.expiresAt ?: "")
            .append('\n')
    }
    return sb.toString().encodeToByteArray()
}

private fun decodeCookies(bytes: ByteArray): List<SessionCookie> =
    bytes.decodeToString().split('\n').mapNotNull { line ->
        if (line.isEmpty()) return@mapNotNull null
        val p = line.split(FIELD)
        if (p.size != 7 || p[0].isEmpty() || p[1].isEmpty()) return@mapNotNull null
        SessionCookie(
            name = p[0],
            value = p[1],
            domain = p[2].removePrefix(".").ifBlank { "youtube.com" },
            path = p[3].ifBlank { "/" },
            secure = p[4] == "1",
            httpOnly = p[5] == "1",
            expiresAt = p[6].toLongOrNull(),
        )
    }

private fun flattenShellContinuations(text: String): String = buildString(text.length) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' || c == '^' || c == '`') {
            val next = text.getOrNull(i + 1)
            if (next == '\n') {
                append(' ')
                i += 2
                continue
            }
            if (next == '\r' && text.getOrNull(i + 2) == '\n') {
                append(' ')
                i += 3
                continue
            }
        }
        if (c == '\n' || c == '\r') append(' ') else append(c)
        i++
    }
}

private val CURL_CMD = Regex("""\bcurl(?:\.exe)?\s+""", RegexOption.IGNORE_CASE)
private val CURL_HEADER_COOKIE = Regex(
    """(?:-H|--header)(?:\s+|=)\$?(['"])\s*[Cc]ookie\s*:\s*(.*?)\1""",
)
private val CURL_COOKIE_FLAG = Regex(
    """(?:(?:^|[\s])(?:-b|--cookie))(?:\s+|=)(['"])(.*?)\1""",
)
private val COOKIE_HEADER_LINE = Regex("""^[Cc]ookie\s*:\s*(.+)$""", RegexOption.MULTILINE)

private fun looksLikeCurl(text: String): Boolean = CURL_CMD.containsMatchIn(text)

private fun cookiePayloadFromPaste(text: String): String? {
    val flat = flattenShellContinuations(text)
    if (looksLikeCurl(flat)) return cookieValueFromCurl(flat).orEmpty()
    return COOKIE_HEADER_LINE.find(text)?.groupValues?.get(1)?.trim()
}

private fun cookieValueFromCurl(flat: String): String? {
    CURL_HEADER_COOKIE.find(flat)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    CURL_COOKIE_FLAG.find(flat)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return null
}

private fun parseCookieHeader(text: String): List<SessionCookie> {
    val collapsed = text.replace('\n', ' ').replace('\r', ' ').trim()
    val body = if (collapsed.startsWith("Cookie:", ignoreCase = true)) {
        collapsed.substringAfter(':').trim()
    } else {
        collapsed
    }
    if (!body.contains('=')) return emptyList()
    return body.split(';').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val name = pair.substring(0, eq).trim()
        val value = pair.substring(eq + 1).trim()
        if (name.isEmpty() || '=' in name || ' ' in name || ':' in name) return@mapNotNull null
        SessionCookie(name, value, domainForName(name), httpOnly = false)
    }
}

private fun parseNameValueLines(lines: List<String>): List<SessionCookie> =
    lines.mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith('#') || t.startsWith("//")) return@mapNotNull null
        val eq = t.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val name = t.substring(0, eq).trim()
        val value = t.substring(eq + 1).trim()
        if (name.isEmpty() || name.contains(' ')) return@mapNotNull null
        SessionCookie(name, value, domainForName(name), httpOnly = false)
    }

private fun parseNetscape(lines: List<String>): List<SessionCookie> =
    lines.mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith('#')) return@mapNotNull null
        val cols = t.split('\t')
        if (cols.size < 7) return@mapNotNull null
        val domain = cols[0].removePrefix("#HttpOnly_").removePrefix(".")
        val secure = cols[3].equals("TRUE", ignoreCase = true)
        val expiry = cols[4].toLongOrNull()?.let { if (it < 1_000_000_000_000L) it * 1000 else it }
        val name = cols[5]
        val value = cols.drop(6).joinToString("\t")
        if (name.isEmpty() || domain.isEmpty()) return@mapNotNull null
        SessionCookie(name, value, domain, cols[2].ifBlank { "/" }, secure, httpOnly = true, expiry)
    }

private fun parseChromeTsv(lines: List<String>): List<SessionCookie> {
    val nonEmpty = lines.filter { it.isNotBlank() }
    if (nonEmpty.isEmpty()) return emptyList()
    val headerCols = nonEmpty[0].split('\t').map { it.trim().lowercase() }
    val hasHeader = headerCols.any { it == "name" } && headerCols.any { it == "value" }
    val nameI = if (hasHeader) headerCols.indexOf("name") else 0
    val valueI = if (hasHeader) headerCols.indexOf("value") else 1
    val domainI = headerIndex(headerCols, hasHeader, "domain", 2)
    val pathI = headerIndex(headerCols, hasHeader, "path", 3)
    val expiresI = headerCols.indexOfFirst { it.startsWith("expire") }
    val httpOnlyI = headerCols.indexOfFirst { it == "httponly" || it == "http only" }
    val rows = if (hasHeader) nonEmpty.drop(1) else nonEmpty
    return rows.mapNotNull { row ->
        val cols = row.split('\t')
        val name = cols.getOrNull(nameI)?.trim().orEmpty()
        val value = cols.getOrNull(valueI).orEmpty()
        if (name.isEmpty() || name.equals("name", ignoreCase = true)) return@mapNotNull null
        val domain = cols.getOrNull(domainI)?.trim()?.removePrefix(".")?.ifBlank { null }
            ?: domainForName(name)
        val path = cols.getOrNull(pathI)?.trim()?.ifBlank { "/" } ?: "/"
        val expiry = cols.getOrNull(expiresI)?.let { parseExpiry(it) }
        val httpOnly = truthy(cols.getOrNull(httpOnlyI))
        SessionCookie(name, value, domain, path, secure = true, httpOnly = httpOnly, expiresAt = expiry)
    }
}

private fun parseJsonCookiePaste(text: String): List<SessionCookie> {
    if (text.firstOrNull() !in setOf('{', '[')) return emptyList()
    val objects = Regex("""\{[^{}]+\}""").findAll(text).map { it.value }.toList()
    return objects.mapNotNull { obj ->
        val name = jsonString(obj, "name") ?: jsonString(obj, "n") ?: return@mapNotNull null
        val value = jsonString(obj, "value") ?: jsonString(obj, "v") ?: return@mapNotNull null
        val domain = (jsonString(obj, "domain") ?: jsonString(obj, "d") ?: domainForName(name))
            .removePrefix(".")
        val path = jsonString(obj, "path") ?: jsonString(obj, "p") ?: "/"
        val secure = jsonBool(obj, "secure") ?: jsonBool(obj, "s") ?: true
        val httpOnly = jsonBool(obj, "httpOnly") ?: jsonBool(obj, "httponly") ?: jsonBool(obj, "h") ?: false
        val expiry = jsonString(obj, "expiresAt")?.toLongOrNull()
            ?: jsonString(obj, "expirationDate")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            ?: jsonString(obj, "e")?.toLongOrNull()
        SessionCookie(name, value, domain, path, secure, httpOnly, expiry)
    }
}

private fun jsonString(obj: String, key: String): String? {
    val m = Regex(""""$key"\s*:\s*("(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?)""").find(obj) ?: return null
    val raw = m.groupValues[1]
    if (raw.startsWith('"')) return unescapeJson(raw.substring(1, raw.length - 1))
    return raw
}

private fun jsonBool(obj: String, key: String): Boolean? {
    val m = Regex(""""$key"\s*:\s*(true|false)""").find(obj) ?: return null
    return m.groupValues[1] == "true"
}

private fun unescapeJson(s: String): String = buildString(s.length) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\' || i == s.lastIndex) {
            append(c)
            i++
            continue
        }
        when (val n = s[i + 1]) {
            'n' -> append('\n')
            'r' -> append('\r')
            't' -> append('\t')
            '"' -> append('"')
            '\\' -> append('\\')
            'u' if i + 5 < s.length -> {
                append(s.substring(i + 2, i + 6).toInt(16).toChar())
                i += 4
            }
            else -> append(n)
        }
        i += 2
    }
}

private fun headerIndex(header: List<String>, hasHeader: Boolean, name: String, fallback: Int): Int {
    if (!hasHeader) return fallback
    val i = header.indexOf(name)
    return if (i >= 0) i else fallback
}

private fun truthy(raw: String?): Boolean {
    val v = raw?.trim()?.lowercase().orEmpty()
    return v == "true" || v == "✓" || v == "✔" || v == "1" || v == "yes"
}

private fun parseExpiry(raw: String): Long? {
    val t = raw.trim()
    t.toLongOrNull()?.let { return if (it in 1 until 1_000_000_000_000L) it * 1000 else it }
    t.toDoubleOrNull()?.let { return (it * 1000).toLong() }
    return null
}

private fun domainForName(@Suppress("UNUSED_PARAMETER") name: String): String = "youtube.com"

private fun String.decodeBase64Url(): ByteArray? {
    val padded = when (length % 4) {
        2 -> "$this=="
        3 -> "$this="
        else -> this
    }.replace('-', '+').replace('_', '/')
    return padded.decodeBase64()?.toByteArray()
}

private const val QR_PREFIX = "ymusic-link:1:"
private const val QR_SEP = "k="
private const val WEBHOOK_ORIGIN = "https://webhook.site"
private const val WEBHOOK_TOKEN = "https://webhook.site/token"
private val WEBHOOK_INBOX = Regex("^https://webhook\\.site/[0-9a-fA-F-]{36}$")
private val UUID_FIELD = Regex(""""uuid"\s*:\s*"([0-9a-fA-F-]{36})"""")
private const val USER_AGENT = "YMusic/4"
private const val KEY_LEN = 32
private const val IV_LEN = 16
private const val MAC_LEN = 32
private const val ENC_LABEL = "enc"
private const val MAC_LABEL = "mac"
private const val SAS_LABEL = "sas"
private const val PERSIST_MS = 400L * 24 * 60 * 60 * 1000
private const val TOKEN_EXPIRY_SEC = 600

internal const val DEVICE_LINK_POLL_MS = 2_000L
internal const val DEVICE_LINK_TIMEOUT_MS = TOKEN_EXPIRY_SEC * 1000L

fun newDeviceLinkHttpClient(): KmpHttpClient = KmpHttpClient.create {}

internal val ESSENTIAL_COOKIE_NAMES = setOf(
    "LOGIN_INFO",
    "SAPISID",
    "APISID",
    "HSID",
    "SSID",
    "SIDCC",
    "__Secure-1PAPISID",
    "__Secure-3PAPISID",
)

fun List<SessionCookie>.looksSignedIn(): Boolean =
    any { it.name in ESSENTIAL_COOKIE_NAMES && it.value.isNotEmpty() }

suspend fun awaitDeviceLinkCookies(
    client: KmpHttpClient,
    offer: DeviceLinkOffer,
): List<SessionCookie> {
    val deadline = DEVICE_LINK_TIMEOUT_MS
    var waited = 0L
    while (waited <= deadline) {
        val cookies = pollDeviceLinkCookies(client, offer)
        if (cookies != null) return cookies
        delay(DEVICE_LINK_POLL_MS)
        waited += DEVICE_LINK_POLL_MS
    }
    deleteDeviceLinkToken(client, offer.tokenId)
    error("timeout")
}
