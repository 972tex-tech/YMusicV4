package kmphttp

/**
 * Platform-specific setup for cookie tests that require PSL (Public Suffix List).
 *
 * - JVM: No setup needed, uses OkHttp's built-in PSL
 * - Native: No setup needed, uses embedded DAFSA
 * - JS/WasmJS: Must configure PSL module (npm:psl) before running tests
 */
expect suspend fun setupCookiePslForTests()
