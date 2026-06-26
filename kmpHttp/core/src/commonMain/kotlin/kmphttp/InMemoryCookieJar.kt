package kmphttp

/**
 * A simple in-memory [CookieJar]: accepts all cookies, keeps them in memory, and returns the
 * matching, unexpired ones for each request. Session (non-persistent) cookies live for the lifetime
 * of this instance; nothing is persisted across process restarts.
 */
expect class InMemoryCookieJar() : CookieJar
