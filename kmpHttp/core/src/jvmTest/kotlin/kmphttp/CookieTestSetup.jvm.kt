package kmphttp

/**
 * JVM: OkHttp has built-in PSL support, no setup needed.
 */
actual suspend fun setupCookiePslForTests() {
    // No-op: JVM uses OkHttp's built-in PublicSuffixDatabase
}
