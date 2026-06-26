package kmphttp

/** On Linux the default KmpHttpClient engine is the libcurl-backed [CurlKmpHttpClient]. */
@Suppress("FunctionName")
actual fun KmpHttpClient.Companion.Builder(): KmpHttpClient.BaseBuilder<*> =
    CurlKmpHttpClient.Builder()
