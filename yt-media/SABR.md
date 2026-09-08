# SABR / UMP

How googlevideo SABR actually works (WEB player). Wire names are protobuf
names. Debug logs: `sel=` = initialized formats, `aud=`/`vid=` = playable
ladders, `br=` = buffered ranges, `40=` = `enabled_track_types_bitfield`.

## Request (`VideoPlaybackAbrRequest`)

The client POSTs one body per SABR round:

| Proto name | Meaning |
|---|---|
| `client_abr_state.player_time_ms` | Playhead. Live head may be `2^53-1` (sentinel). |
| `client_abr_state.enabled_track_types_bitfield` | `0` = audio+video, `1` = audio only (server sends no video). |
| `selected_format_ids` | **Initialized** formats. Empty = nothing initialized yet. |
| `buffered_ranges` | What the client has per itag (`start_ms`, `duration_ms`, seq span). |
| `selected_audio_format_ids` | **Playable** audio formats (ladder), not “the current track”. |
| `selected_video_format_ids` | **Playable** video formats (ladder), not `IY(video)`. |

The bound video track (`IY(video)`) is whatever the player already initialized
and is lockstep-watching. On WEB that is leftover in `buffered_ranges` (e.g.
itag 243), **not** `selected_video_format_ids[0]`.

## Server loop

Server picks an itag from the playable ladders (ABR). Then **both** checks, in
order:

1. If that itag is **not** in `selected_format_ids` (`sel=`; WEB live is
   empty every request) → UMP `FORMAT_INIT` (part 42). On **live** the
   following `MEDIA_HEADER` is **media** (`seq`/`start_ms` at the live
   head, no `is_init_seg`, no `start_range`) — not an init segment.
2. Always: if that itag’s `(buffered_end − playerTime) < minReadahead`
   → more **media** segments.

`sel` empty ⇒ (1) fires every body. That is **not** an init segment:
gold live (`BEtsctac5KQ`) repeats `FORMAT_INIT(itag)` then `MH(seq=live,
t=live ms)` with no `is_init_seg` / `start_range`. ABR 243→302 is the
same shape at the live head, not seek 0.

Initialized + readahead already enough → no media (backoff / `NEXT_REQUEST_POLICY`).
Initialized + readahead short → **media only**, no init.

`FORMAT_INIT` **plus** an init-shaped `MH` (`is_init_seg`, `start_range=0`,
`seq=1`) is a **new stream**: new SourceBuffer, seek to 0 (live DVR start).

## UMP body

Framing: `partId` + size + payload (UMP varint, not protobuf varint).

| Part | Id | Role |
|---|---|---|
| `MEDIA_HEADER` | 20 | VOD: itag, lmt, `start_range`, `is_init_seg`, seq, `start_ms`, `duration_ms`, `content_length`. Gold live omits those byte-range fields; sends seq, `start_ms`, FormatId (13), TimeRange (15: startTicks/timescale), VH=1 (16). |
| `MEDIA_DATA` | 21 | 1-byte headerId + bytes |
| `MEDIA_END` | 22 | headerId |
| `LIVE_METADATA` | 31 | live only — broadcast / live-head clock |
| `NEXT_REQUEST_POLICY` | 35 | backoff / target readahead |
| `FORMAT_INIT` | 42 | register a format. Gold live: `video_id` + itag-only FormatId, every `sel=-` body |
| `STREAM_PROTECTION_STATUS` | 58 | `1` ok, `2`/`3` po-rejected |

Lockstep: audio cannot execute past last ingested video `MEDIA_HEADER` seq
on leftover IY. Gold moves that by ingesting video UMP. Audio-only (`40=1`)
sends none. We do **not** poke `base.js` fields (`Cx` / `Ds.L` rotate with
the player hash). Only MSE (`buffered` cover, drop video) and the SABR
request (`40=1`).

`start_range` is a VOD byte cursor in one contiguous file. Missing / `0`
looks like start-of-file there. Gold **live** media `MH` omits it (and
`lmt` / `duration_ms` / `content_length`): segments are seq/`start_ms`,
~1s apart.

## Gapless quality switch (WEB MSE)

Captured 2026-09-08: VOD `x72VDoEpTIU` auto↔tiny↔auto (AV1 398↔394);
live DVR `0_LTL7O1xX0` auto↔tiny↔auto (VP9 302↔278↔303). Logs:
`playground/build/gapless-{vod,live}.log`. Proof filter also records
`changeType` / `appendBuffer`.

### `FORMAT_INIT` (UMP 42)

Register a format the client has not initialized (`itag` not in `sel=`).
Fields on these bodies:

| Field | Wire | Example |
|---|---|---|
| 1 | string | `video_id` |
| 2 | FormatId | `itag` + `lmt` (+ `xtag` on DRC audio) |
| 3 | varint | duration ms (VOD length, or DVR window ~8h) |
| 4 | varint | 174 AV1 / 4837 VP9 / 86–2902 audio |
| 5 | string | mime (`video/mp4; codecs="av01.0.08M.08"`, `video/webm; codecs="vp09.00.40.08"`, `audio/webm; codecs="opus"`) |
| 6, 7 | bytes | short (5–7 B) |
| 9 | varint | VOD 13065216; live often repeats field 3 |
| 10 | varint | timescale (15360 AV1, 1000 WebM) |

Not an init segment. The init bytes are the next `MEDIA_HEADER` with `is_init_seg=1`.

### `MEDIA_HEADER` (UMP 20)

| Field | Init (`8=1`) | Media (`8=0`) |
|---|---|---|
| 1 `header_id` | yes | yes |
| 2 `video_id` | yes | yes |
| 3 `itag` | yes | yes |
| 4 `lmt` | yes | yes |
| 5 `xtags` | audio DRC | audio DRC |
| 6 `start_range` | **0** | byte offset in that itag’s file (prev `range+clen`) |
| 8 `is_init_seg` | 1 | 0 |
| 9 `sequence_number` | omit | GOP/segment index (VOD from 1; live continues) |
| 10 | size/bitrate hint | yes |
| 11 `start_ms` | omit | presentation start |
| 12 `duration_ms` | omit | GOP length (~5s AV1, ~5–6s VP9, 10s opus) |
| 13 FormatId | itag+lmt | itag+lmt |
| 14 `content_length` | init size (AV1 ~2820, VP9 ~90k, opus ~1.7k–53k) | media size |
| 15 TimeRange | omit | `1=startTicks 2=durationTicks 3=timescale` |

This DVR live **does** send `start_range` / `lmt` / `clen` / `duration_ms`
(same shape as VOD). Gold miller live (`BEtsctac5KQ`) omitted those; do not
treat “live ⇒ no byte range” as universal.

UMP order for a **new** itag:

```
FORMAT_INIT(itag)
MEDIA_HEADER init=1 range=0     MEDIA_DATA(init) MEDIA_END
MEDIA_HEADER init=0 seq=N t=T   MEDIA_DATA...    MEDIA_END
```

Already in `sel=` → no `FORMAT_INIT`; media `MH` only.

### What MSE does

`SourceBuffer.changeType` was **never** called. Mime stays generic:
`video/mp4; codecs="av01.0.08M.08"` across 398↔394, `video/webm; codecs="vp9"`
across 302↔278↔303. Tiny’s FORMAT_INIT codec (`av01.0.00M.08` / `vp09.00.11.08`)
is SABR metadata, not the SourceBuffer type.

`appendBuffer` of the new init does **not** clear leftover. After auto-back
on live: video `br=[0+28233]` (278 leftover), then FORMAT_INIT 303 + init MH
+ media `seq=6 startMs=28233` → append grows `br` to `[0+34233]`. Clock does
not seek. That is gapless: new GOP is glued at leftover end.

Forced tiny right after join **recreated MediaSource** (`sourceopen` again,
clock 0, `seq=1 t=0`). That is a new stream, not the leftover path.

### Two shapes

| | Forced tiny at join | Auto-back (gapless) |
|---|---|---|
| MediaSource | recreate, clock 0 | keep |
| `changeType` | no | no |
| FORMAT_INIT | new itag | new itag |
| init MH | `range=0` | `range=0` |
| first media MH | `seq=1 startMs=0` | `seq` / `startMs` = leftover end |
| MSE `buffered` | empty then fill from 0 | leftover stays, then extends |

C leftover-keep (`vpar_next`, switch at next key) is what makes the init
append of a different size not punch a hole. YouTube never seeks `currentTime`.

## VOD vs live

Same UMP schema. Live adds `LIVE_METADATA`, URL `live=1` (uncached), huge
seq/`start_ms` at the live edge, sliding `buffered_ranges`, and an **open**
timeline (duration Infinity or a DVR window). VOD duration is finite. Live
leftover is a few seconds; after it drains, video `Cx` must keep moving or
audio stops.

## Audio-only (product `"zero"`)

`"zero"` is a player wire token. Two surfaces:

**VOD:** `__ymAudioOnly` + SABR field `40=1`. Dummy `SourceBuffer.buffered`
keeps drain’s video leftover non-empty. Do **not** wipe C video leftover
(that snapped audio ~0.5s). Rest the decoder (`__mseRestVideo(1)`) and
keep packets; clock follows audio while rest is on. Load-as-zero still
pins `"tiny"` so ABR binds a ladder itag. Mid-play zero does not pin.

**Live:** do **not** set field 40 (lockstep dies). Pin `"tiny"` (144p), keep
appending video as a normal quality switch, and rest the C video decoder
(`__mseRestVideo(1)`) so the picture is black. SABR / MSE / leftover are
untouched.

Do not invent a video UMP tail. Do not forge `selected_format_ids` /
`buffered_ranges`. Do not write YouTube player fields (`Cx`, `Ds.L`, …).
