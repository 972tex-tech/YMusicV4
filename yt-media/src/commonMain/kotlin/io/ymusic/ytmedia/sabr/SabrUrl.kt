package io.ymusic.ytmedia.sabr

/** Tiny query-param editor — commonMain has no URL builder and we only need set/append. */
internal fun String.setQueryParam(key: String, value: String): String {
    val encoded = "$key=$value"
    val qIndex = indexOf('?')
    if (qIndex < 0) return "$this?$encoded"
    val prefix = substring(0, qIndex)
    val query = substring(qIndex + 1)
    val kept = query.split('&')
        .filter { it.isNotEmpty() && it.substringBefore('=') != key }
    return buildString {
        append(prefix)
        append('?')
        append((kept + encoded).joinToString("&"))
    }
}

private const val CPN_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/** 16-char client playback nonce, YouTube's alphabet. */
internal fun generateCpn(random: kotlin.random.Random): String =
    buildString(16) { repeat(16) { append(CPN_ALPHABET[random.nextInt(CPN_ALPHABET.length)]) } }
