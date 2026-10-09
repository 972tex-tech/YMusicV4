package kmphttp.internal

import kmphttp.Request

internal actual fun Request.withCallLog(call: HttpCallLog): Request = this
