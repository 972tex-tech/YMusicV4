package kmphttp

/** Creates a fresh disk-backed [Cache] for the cache smoke tests (platform-specific temp dir). */
expect fun newTestCache(): Cache
