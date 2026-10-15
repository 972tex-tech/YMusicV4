package kmphttp

/**
 * True when this throwable (not its causes) is a connectivity failure — DNS, connect, reset/abort,
 * TLS transport, timeout — that a better connection would fix. Disk, protocol, parse and certificate or
 * pin rejections are false even though they are [okio.IOException]s too.
 */
expect fun Throwable.isTransportFailure(): Boolean
