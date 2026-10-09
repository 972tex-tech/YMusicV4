package kmphttp.internal

import kmphttp.Request

internal actual fun Request.withCallLog(call: HttpCallLog): Request =
    newBuilder().tag(HttpCallLog::class.java, call).build()
