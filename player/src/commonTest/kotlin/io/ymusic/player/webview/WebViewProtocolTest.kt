package io.ymusic.player.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebViewProtocolTest {
    private val json = Json

    private fun parse(s: String) = json.parseToJsonElement(s).jsonObject

    @Test
    fun load_batches_config_and_converts_ms_to_seconds() {
        val cmd = parse(
            WebViewProtocol.encodeLoad(
                videoId = "dQw4w9WgXcQ", startPositionMs = 30_000, playWhenReady = true,
                volume = 0.5f, speed = 1.5f, audioOnly = true,
            ),
        )
        assertEquals(12, cmd["what"]!!.jsonPrimitive.content.toInt()) // BULK
        val actions = cmd["actions"]!!.jsonArray
        val load = actions[0].jsonObject
        assertEquals(0, load["what"]!!.jsonPrimitive.content.toInt())
        assertEquals("dQw4w9WgXcQ", load["videoId"]!!.jsonPrimitive.content)
        assertEquals(30.0, load["startTimeSeconds"]!!.jsonPrimitive.content.toDouble())
        assertEquals(true, load["playWhenReady"]!!.jsonPrimitive.content.toBoolean())
        // audio-only load carries a quality:"zero" action in the batch.
        val quality = actions.map { it.jsonObject }.first { it["what"]!!.jsonPrimitive.content.toInt() == 3 }
        assertEquals("zero", quality["quality"]!!.jsonPrimitive.content)
    }

    @Test
    fun transport_commands_carry_the_right_discriminators() {
        assertEquals(2, parse(WebViewProtocol.encodePlay())["what"]!!.jsonPrimitive.content.toInt())
        assertEquals(false, parse(WebViewProtocol.encodePause())["playWhenReady"]!!.jsonPrimitive.content.toBoolean())
        val seek = parse(WebViewProtocol.encodeSeek(2500))
        assertEquals(4, seek["what"]!!.jsonPrimitive.content.toInt())
        assertEquals(2.5, seek["positionSeconds"]!!.jsonPrimitive.content.toDouble())
        assertEquals(5, parse(WebViewProtocol.encodeStop())["what"]!!.jsonPrimitive.content.toInt())
        assertEquals(0.25f, parse(WebViewProtocol.encodeSetVolume(0.25f))["volume"]!!.jsonPrimitive.content.toFloat())
    }

    @Test
    fun volume_is_clamped() {
        assertEquals(1.0f, parse(WebViewProtocol.encodeSetVolume(4f))["volume"]!!.jsonPrimitive.content.toFloat())
        assertEquals(0.0f, parse(WebViewProtocol.encodeSetVolume(-1f))["volume"]!!.jsonPrimitive.content.toFloat())
    }

    @Test
    fun audio_only_maps_to_video_quality() {
        assertEquals("zero", parse(WebViewProtocol.encodeSetAudioOnly(true))["quality"]!!.jsonPrimitive.content)
        assertEquals("auto", parse(WebViewProtocol.encodeSetAudioOnly(false))["quality"]!!.jsonPrimitive.content)
    }

    @Test
    fun bulk_wraps_commands_in_order_and_a_single_one_passes_through() {
        val volume = WebViewProtocol.encodeSetVolume(0.5f)
        val quality = WebViewProtocol.encodeSelectVideoQuality("hd720")
        val bulk = parse(WebViewProtocol.encodeBulk(listOf(volume, quality)))
        assertEquals(12, bulk["what"]!!.jsonPrimitive.content.toInt()) // BULK
        val actions = bulk["actions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(10, actions[0]["what"]!!.jsonPrimitive.content.toInt()) // SET_VOLUME first
        assertEquals("hd720", actions[1]["quality"]!!.jsonPrimitive.content)

        assertEquals(quality, WebViewProtocol.encodeBulk(listOf(quality)))
    }

    @Test
    fun decodes_state_changed_with_session_prefix() {
        val e = WebViewProtocol.decodeEvent(
            "1783842105_state_changed",
            """{"newState":"ready","playWhenReady":true}""",
            sessionId = "1783842105",
        )
        assertTrue(e is WebViewEvent.StateChanged)
        assertEquals(WebPlaybackState.Ready, e.state)
        assertEquals(true, e.playWhenReady)
    }

    @Test
    fun decodes_progress_seconds_to_ms() {
        val e = WebViewProtocol.decodeEvent(
            "sid_progress",
            """{"videoId":"x","seekable":true,"positionSeconds":12.5,"durationSeconds":200,"bufferedSeconds":30}""",
            sessionId = "sid",
        )
        assertTrue(e is WebViewEvent.Progress)
        assertEquals(12_500, e.positionMs)
        assertEquals(200_000, e.durationMs)
        assertEquals(30_000, e.bufferedMs)
        assertTrue(e.seekable)
    }

    @Test
    fun connected_and_error_channels() {
        assertEquals(WebViewEvent.Connected, WebViewProtocol.decodeEvent("s_connected", "{}", "s"))
        val err = WebViewProtocol.decodeEvent(
            "s_state_changed",
            """{"newState":"error","playWhenReady":false,"code":"UNPLAYABLE","message":"nope"}""",
            "s",
        )
        assertTrue(err is WebViewEvent.StateChanged)
        assertEquals(WebPlaybackState.Error, err.state)
        assertEquals("UNPLAYABLE", err.errorCode)
        assertTrue(err.errorCanRetry, "absent canRetry defaults true (legacy host behavior)")
        assertFalse(err.errorNeedReset, "absent needReset defaults false (legacy host behavior)")
    }

    @Test
    fun error_retry_flags_come_off_the_wire() {
        val err = WebViewProtocol.decodeEvent(
            "s_state_changed",
            """{"newState":"error","playWhenReady":false,"code":"SIGN_IN","needReset":true,"canRetry":false}""",
            "s",
        )
        assertTrue(err is WebViewEvent.StateChanged)
        assertFalse(err.errorCanRetry)
        assertTrue(err.errorNeedReset)
    }

    @Test
    fun capability_commands_carry_the_right_discriminators() {
        assertEquals(3, parse(WebViewProtocol.encodeSelectVideoQuality("hd720"))["what"]!!.jsonPrimitive.content.toInt())
        assertEquals("hd720", parse(WebViewProtocol.encodeSelectVideoQuality("hd720"))["quality"]!!.jsonPrimitive.content)
        assertEquals(7, parse(WebViewProtocol.encodeSelectCaption("en"))["what"]!!.jsonPrimitive.content.toInt())
        assertEquals(8, parse(WebViewProtocol.encodeSelectAudioTrack("t1"))["what"]!!.jsonPrimitive.content.toInt())
        assertEquals(9, parse(WebViewProtocol.encodeSetCaptionVisible(true))["what"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, parse(WebViewProtocol.encodeSetCaptionVisible(true))["visible"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun decodes_quality_captions_audiotracks() {
        val q = WebViewProtocol.decodeEvent(
            "s_quality_changed",
            """{"available":[{"quality":"hd720","label":"720p"},{"quality":"auto","label":"Auto"}],"actual":"hd720","prefer":"auto"}""",
            "s",
        )
        assertTrue(q is WebViewEvent.QualityChanged)
        assertEquals(2, q.state.options.size)
        assertEquals("720p", q.state.options.first().label)
        assertEquals("hd720", q.state.actualQuality)
        assertEquals("auto", q.state.preferredQuality)

        val c = WebViewProtocol.decodeEvent(
            "s_captions",
            """{"tracks":[{"languageCode":"en","displayName":"English"}],"selectedCaptionLanguage":"en"}""",
            "s",
        )
        assertTrue(c is WebViewEvent.CaptionsChanged)
        assertEquals("English", c.state.tracks.single().displayName)
        assertEquals("en", c.state.selectedLanguage)

        val a = WebViewProtocol.decodeEvent(
            "s_audio_tracks",
            """{"tracks":[{"trackId":"t1","displayName":"Original"}],"selectedTrackId":"t1"}""",
            "s",
        )
        assertTrue(a is WebViewEvent.AudioTracksChanged)
        assertEquals("t1", a.state.tracks.single().id)
        assertEquals("t1", a.state.selectedId)
    }

    @Test
    fun encodes_extraction_commands() {
        // BULK [AddWantedPlayerRes(13), Preload(1)] — want registered before the preload fetch (matches
        // legacy wantedPlayerRes). Preload fetches /player without playing; no video is loaded on screen.
        val pr = parse(WebViewProtocol.encodeRequestPlayerResponse("abc"))
        assertEquals(12, pr["what"]!!.jsonPrimitive.content.toInt())
        val actions = pr["actions"]!!.jsonArray
        assertEquals(13, actions[0].jsonObject["what"]!!.jsonPrimitive.content.toInt())
        assertEquals("abc", actions[0].jsonObject["videoId"]!!.jsonPrimitive.content)
        assertEquals(1, actions[1].jsonObject["what"]!!.jsonPrimitive.content.toInt())
        assertEquals("abc", actions[1].jsonObject["videoId"]!!.jsonPrimitive.content)

        val pt = parse(WebViewProtocol.encodeRequestPoToken("abc", 30_000))
        assertEquals(16, pt["what"]!!.jsonPrimitive.content.toInt())
        assertEquals("abc", pt["videoId"]!!.jsonPrimitive.content)
        assertEquals(30_000, pt["timeout"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun decodes_player_response_keeping_data_opaque() {
        val e = WebViewProtocol.decodeEvent(
            "s_player_res",
            """{"videoId":"vid","data":{"streamingData":{"serverAbrStreamingUrl":"https://x/videoplayback?sabr=1"},"clientName":"WEB"}}""",
            "s",
        )
        assertTrue(e is WebViewEvent.PlayerResponse)
        assertEquals("vid", e.videoId)
        // data round-trips as a JSON object string the SABR layer can parse.
        val data = json.parseToJsonElement(e.dataJson).jsonObject
        assertEquals("WEB", data["clientName"]!!.jsonPrimitive.content)
        // missing videoId or data → ignored
        assertNull(WebViewProtocol.decodeEvent("s_player_res", """{"videoId":"vid"}""", "s"))
    }

    @Test
    fun decodes_po_token_success_and_failure() {
        val ok = WebViewProtocol.decodeEvent("s_po_token_v2", """{"videoId":"vid","success":true,"token":"AABB"}""", "s")
        assertTrue(ok is WebViewEvent.PoToken)
        assertEquals("AABB", ok.token)
        assertNull(ok.errorMessage)

        val bad = WebViewProtocol.decodeEvent("s_po_token_v2", """{"videoId":"vid","success":false,"message":"no minter"}""", "s")
        assertTrue(bad is WebViewEvent.PoToken)
        assertNull(bad.token)
        assertEquals("no minter", bad.errorMessage)
    }

    @Test
    fun wrong_session_and_unknown_channel_are_ignored() {
        // Unknown channel name (after stripping) → null.
        assertNull(WebViewProtocol.decodeEvent("sid_bogus", "{}", "sid"))
        // A different session's channel keeps its prefix → treated as an unknown name → null.
        assertNull(WebViewProtocol.decodeEvent("OTHER_state_changed", "{}", "sid"))
    }
}
