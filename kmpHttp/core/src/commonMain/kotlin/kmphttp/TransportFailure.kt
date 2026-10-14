package kmphttp

/**
 * True when this throwable (not its causes) is a connectivity failure — DNS, connect, reset/abort,
 * TLS, timeout — that a better connection would fix. Disk, protocol and parse failures are false
 * even though they are [okio.IOException]s too.
 */
expect fun Throwable.isTransportFailure(): Boolean
