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
    var sort: List<YtChip> = emptyList()
    var chipsWrap = false
    var emptyTitle: String? = null
    var emptyMessage: String? = null
    var filterGroups: List<YtFilterGroup> = emptyList()
    var pageHeader: YtPageHeader? = null
    var pageTabs: List<YtPageTab> = emptyList()
    var autoplayVideoId: String? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "sections" -> sections = r.readArrayOf { readSection(it) }
            "continuation" -> continuation = r.stringOrNull()
            "chips" -> chips = r.readArrayOf { readChip(it) }
            "sort" -> sort = r.readArrayOf { readChip(it) }
            "chipsWrap" -> chipsWrap = r.boolean()
            "emptyTitle" -> emptyTitle = r.stringOrNull()
            "emptyMessage" -> emptyMessage = r.stringOrNull()
            "filterGroups" -> filterGroups = r.readArrayOf { readFilterGroup(it) }
            "pageHeader" -> pageHeader = readPageHeader(r)
            "pageTabs" -> pageTabs = r.readArrayOf { readPageTab(it) }
            "autoplayVideoId" -> autoplayVideoId = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtFeedPage(
        sections, continuation, chips, sort, chipsWrap, emptyTitle, emptyMessage,
        filterGroups, pageHeader, pageTabs, autoplayVideoId,
    )
}

private suspend fun readPageHeader(r: JsonReader): YtPageHeader {
    var title: String? = null
    var subtitleLine1: String? = null
    var subtitleLine2: String? = null
    var thumbs: List<YtThumb> = emptyList()
    var banner: List<YtThumb> = emptyList()
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "title" -> title = r.stringOrNull()
            "subtitleLine1" -> subtitleLine1 = r.stringOrNull()
            "subtitleLine2" -> subtitleLine2 = r.stringOrNull()
            "thumbs" -> thumbs = r.readArrayOf { readThumb(it) }
            "banner" -> banner = r.readArrayOf { readThumb(it) }
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtPageHeader(title, subtitleLine1, subtitleLine2, thumbs, banner)
}

private suspend fun readPageTab(r: JsonReader): YtPageTab {
    var title = ""
    var params: String? = null
    var url: String? = null
    var home = false
    var selected = false
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "title" -> title = r.stringOrNull().orEmpty()
            "params" -> params = r.stringOrNull()
            "url" -> url = r.stringOrNull()
            "home" -> home = r.boolean()
            "selected" -> selected = r.boolean()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtPageTab(title, params, url, home, selected)
}

private suspend fun readFilterGroup(r: JsonReader): YtFilterGroup {
    var title = ""
    var options: List<YtFilterOption> = emptyList()
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "title" -> title = r.stringOrNull().orEmpty()
            "options" -> options = r.readArrayOf { readFilterOption(it) }
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtFilterGroup(title, options)
}

private suspend fun readFilterOption(r: JsonReader): YtFilterOption {
    var label = ""
    var params: String? = null
    var selected = false
    var disabled = false
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "label" -> label = r.stringOrNull().orEmpty()
            "params" -> params = r.stringOrNull()
            "selected" -> selected = r.boolean()
            "disabled" -> disabled = r.boolean()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtFilterOption(label, params, selected, disabled)
}

private suspend fun readSection(r: JsonReader): YtSection {
    var header: YtSectionHeader? = null
    var layout = YtSection.LAYOUT_LIST
    var items: List<YtFeedItem> = emptyList()
    var collapsible = false
    var collapsedRows: List<Int> = emptyList()
    var continuation: String? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "header" -> header = readSectionHeader(r)
            "layout" -> layout = r.stringOrNull() ?: YtSection.LAYOUT_LIST
            "items" -> items = r.readArrayOf { readItem(it) }
            "collapsible" -> collapsible = r.boolean()
            "collapsedRows" -> collapsedRows = r.readArrayOf { it.intOrNull() }.filterNotNull()
            "continuation" -> continuation = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtSection(header, layout, items, collapsible, collapsedRows, continuation)
}

private suspend fun readSectionHeader(r: JsonReader): YtSectionHeader {
    var title: String? = null
    var titleKey: String? = null
    var iconThumbs: List<YtThumb> = emptyList()
    var count: String? = null
    var ctaText: String? = null
    var ctaToken: String? = null
    var ctaDeeplink: String? = null
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "title" -> title = r.stringOrNull()
            "titleKey" -> titleKey = r.stringOrNull()
            "iconThumbs" -> iconThumbs = r.readArrayOf { readThumb(it) }
            "count" -> count = r.stringOrNull()
            "ctaText" -> ctaText = r.stringOrNull()
            "ctaToken" -> ctaToken = r.stringOrNull()
            "ctaDeeplink" -> ctaDeeplink = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtSectionHeader(title, titleKey, iconThumbs, count, ctaText, ctaToken, ctaDeeplink)
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
    var url: String? = null
    var thumbs: List<YtThumb> = emptyList()
    var duration: String? = null
    var itemCount: String? = null
    var live = false
    var musicVideo = false
    var album = false
    var ownerBadge: String? = null
    var accent: String? = null
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
            "url" -> url = r.stringOrNull()
            "thumbs" -> thumbs = r.readArrayOf { readThumb(it) }
            "duration" -> duration = r.stringOrNull()
            "itemCount" -> itemCount = r.stringOrNull()
            "live" -> live = r.boolean()
            "musicVideo" -> musicVideo = r.boolean()
            "album" -> album = r.boolean()
            "ownerBadge" -> ownerBadge = r.stringOrNull()
            "accent" -> accent = r.stringOrNull()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtFeedItem(
        kind, id, title, subtitleLine1, subtitleLine2, ctaText, channelId, url, thumbs,
        duration, itemCount, live, musicVideo, album, ownerBadge, accent,
    )
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

/** The `yt/account` result — the signed-in account card (absent fields default). */
internal suspend fun readAccount(r: JsonReader): YtAccountInfo {
    var signedIn = false
    var name: String? = null
    var email: String? = null
    var handle: String? = null
    var channelId: String? = null
    var thumbs: List<YtThumb> = emptyList()
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "signedIn" -> signedIn = r.boolean()
            "name" -> name = r.stringOrNull()
            "email" -> email = r.stringOrNull()
            "handle" -> handle = r.stringOrNull()
            "channelId" -> channelId = r.stringOrNull()
            "thumbs" -> thumbs = r.readArrayOf { readThumb(it) }
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtAccountInfo(signedIn, name, email, handle, channelId, thumbs)
}

/** The `yt/login/status` result — the cookie-only sign-in gate: `{"signedIn":bool}`. */
internal suspend fun readSignedIn(r: JsonReader): Boolean {
    var signedIn = false
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "signedIn" -> signedIn = r.boolean()
            else -> r.skipValue()
        }
    }
    r.endObject()
    return signedIn
}

/** The `yt/login/url` result: `{"url":"…"}`. */
internal suspend fun readLoginUrl(r: JsonReader): String {
    var url = ""
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "url" -> url = r.stringOrNull() ?: ""
            else -> r.skipValue()
        }
    }
    r.endObject()
    return url
}

/** The `yt/locale` / `yt/locales` result: `{"hl","gl","languages"?,"regions"?}`. */
internal suspend fun readLocales(r: JsonReader): YtLocales {
    var gl = ""
    var languages: List<YtLocaleOption> = emptyList()
    var regions: List<YtLocaleOption> = emptyList()
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "gl" -> gl = r.stringOrNull() ?: ""
            "languages" -> languages = r.readArrayOf { readLocaleOption(it) }
            "regions" -> regions = r.readArrayOf { readLocaleOption(it) }
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtLocales(gl, languages, regions)
}

private suspend fun readLocaleOption(r: JsonReader): YtLocaleOption {
    var code = ""
    var label = ""
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "code" -> code = r.stringOrNull() ?: ""
            "label" -> label = r.stringOrNull() ?: ""
            else -> r.skipValue()
        }
    }
    r.endObject()
    return YtLocaleOption(code, label)
}

/** The `yt/embed` result: `{"html":"…"}`. */
internal suspend fun readEmbedHtml(r: JsonReader): String {
    var html = ""
    r.beginObject()
    while (r.hasNextObjectKey()) {
        when (r.nextKey()) {
            "html" -> html = r.stringOrNull() ?: ""
            else -> r.skipValue()
        }
    }
    r.endObject()
    return html
}
