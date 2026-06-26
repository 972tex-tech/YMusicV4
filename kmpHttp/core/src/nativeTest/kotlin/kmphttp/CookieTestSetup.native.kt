package kmphttp

/**
 * Native: Uses embedded DAFSA for PSL, no setup needed.
 */
actual suspend fun setupCookiePslForTests() {
    // No-op: Native uses embedded DAFSA in PublicSuffixList
}
