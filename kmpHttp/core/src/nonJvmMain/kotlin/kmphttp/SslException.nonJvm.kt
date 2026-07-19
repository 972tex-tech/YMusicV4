package kmphttp

import okio.IOException

actual open class SslException actual constructor(message: String) : IOException(message)
