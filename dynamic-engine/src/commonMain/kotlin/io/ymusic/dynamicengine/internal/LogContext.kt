package io.ymusic.dynamicengine.internal

import kmpcommon.log.Crumb
import kmpcommon.log.Logger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The caller's trail (threaded down in the head's `logCtx`) grafted onto [logger], with this
 *  protocol's [tag] hop appended — the logger every birth-site log in the serve is tagged with.
 *  An absent/garbage trail degrades to just the hop. */
internal fun protocolLogger(logger: Logger?, head: JsonObject, tag: String): Logger? =
    logger?.forkAll(readTrail(head))?.fork(tag)

private fun readTrail(head: JsonObject): List<Crumb> =
    (head["logCtx"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull?.let(::Crumb) }
