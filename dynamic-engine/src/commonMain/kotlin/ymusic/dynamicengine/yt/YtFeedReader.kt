package ymusic.dynamicengine.yt

import kmphttp.json.JsonReader
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// Hand-parse the wire shapes (yt/wire.ts) straight off the streaming pipe with :kmpHttp:json's pull
// reader — no reflective deserializer, nothing buffered whole. Unknown keys are skipped and absent
// fields fall to their defaults, so a newer remote bundle that adds fields never breaks an old app.

/** Read one scalar as a string (null for JSON null / a non-primitive value). */
private suspend fun JsonReader.stringOrNull(): String? = (nextValue() as? JsonPrimitive)?.contentOrNull

/** Read one scalar as a boolean (false for anything non-boolean). */
private suspend fun JsonReader.boolean(): Boolean = (nextValue() as? JsonPrimitive)?.booleanOrNull ?: false

/** Read one scalar as an int (null for anything non-numeric). */
private suspend fun JsonReader.intOrNull(): Int? = (nextValue() as? JsonPrimitive)?.intOrNull

private suspend inline fun <T> JsonReader.readArrayOf(element: (JsonReader) -> T): List<T> {
    val out = mutableListOf<T>()
    beginArray()
    while (hasNextArrayElement()) out.add(element(this))
    endArray()
    return out
}

internal suspend fun readFeedPage(r: JsonReader): YtFeedPage {
    var sections: List<YtSection> = emptyList()
    var continuation: String? = null
    var chips: List<YtChip> = emptyList()
    var autoplayVideoId: String? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "sections" -> sections = r.readArrayOf { readSection(it) }
            "continuation" -> continuation = r.stringOrNull()
            "chips" -> chips = r.readArrayOf { readChip(it) }
            "autoplayVideoId" -> autoplayVideoId = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtFeedPage(sections, continuation, chips, autoplayVideoId)
}

private suspend fun readSection(r: JsonReader): YtSection {
    var header: YtSectionHeader? = null
    var layout = YtSection.LAYOUT_LIST
    var items: List<YtFeedItem> = emptyList()
    var collapsible = false
    var continuation: String? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "header" -> header = readSectionHeader(r)
            "layout" -> layout = r.stringOrNull() ?: YtSection.LAYOUT_LIST
            "items" -> items = r.readArrayOf { readItem(it) }
            "collapsible" -> collapsible = r.boolean()
            "continuation" -> continuation = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtSection(header, layout, items, collapsible, continuation)
}

private suspend fun readSectionHeader(r: JsonReader): YtSectionHeader {
    var title: String? = null
    var iconThumbs: List<YtThumb> = emptyList()
    var count: String? = null
    var ctaText: String? = null
    var ctaToken: String? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "title" -> title = r.stringOrNull()
            "iconThumbs" -> iconThumbs = r.readArrayOf { readThumb(it) }
            "count" -> count = r.stringOrNull()
            "ctaText" -> ctaText = r.stringOrNull()
            "ctaToken" -> ctaToken = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtSectionHeader(title, iconThumbs, count, ctaText, ctaToken)
}

private suspend fun readThumb(r: JsonReader): YtThumb {
    var url = ""
    var w: Int? = null
    var h: Int? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "url" -> url = r.stringOrNull() ?: ""
            "w" -> w = r.intOrNull()
            "h" -> h = r.intOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtThumb(url, w, h)
}

private suspend fun readItem(r: JsonReader): YtFeedItem {
    var kind = ""
    var id = ""
    var title = ""
    var subtitleLine1: String? = null
    var subtitleLine2: String? = null
    var ctaText: String? = null
    var channelId: String? = null
    var thumbs: List<YtThumb> = emptyList()
    var duration: String? = null
    var itemCount: String? = null
    var live = false
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "kind" -> kind = r.stringOrNull() ?: ""
            "id" -> id = r.stringOrNull() ?: ""
            "title" -> title = r.stringOrNull() ?: ""
            "subtitleLine1" -> subtitleLine1 = r.stringOrNull()
            "subtitleLine2" -> subtitleLine2 = r.stringOrNull()
            "ctaText" -> ctaText = r.stringOrNull()
            "channelId" -> channelId = r.stringOrNull()
            "thumbs" -> thumbs = r.readArrayOf { readThumb(it) }
            "duration" -> duration = r.stringOrNull()
            "itemCount" -> itemCount = r.stringOrNull()
            "live" -> live = r.boolean()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtFeedItem(kind, id, title, subtitleLine1, subtitleLine2, ctaText, channelId, thumbs, duration, itemCount, live)
}

private suspend fun readChip(r: JsonReader): YtChip {
    var title = ""
    var token: String? = null
    var selected = false
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "title" -> title = r.stringOrNull() ?: ""
            "token" -> token = r.stringOrNull()
            "selected" -> selected = r.boolean()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtChip(title, token, selected)
}

/** The `yt/suggest` result: `{"suggestions":["…"]}`. */
internal suspend fun readSuggestions(r: JsonReader): List<String> {
    var suggestions: List<String> = emptyList()
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "suggestions" -> suggestions = r.readArrayOf { it.stringOrNull() ?: "" }
            else -> r.skipValue()
        }
    }
    r.endObject()
    return suggestions
}
