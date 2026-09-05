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

## VOD vs live

Same UMP schema. Live adds `LIVE_METADATA`, URL `live=1` (uncached), huge
seq/`start_ms` at the live edge, sliding `buffered_ranges`, and an **open**
timeline (duration Infinity or a DVR window). VOD duration is finite. Live
leftover is a few seconds; after it drains, video `Cx` must keep moving or
audio stops.

## Audio-only (product `"zero"`)

`"zero"` is a player wire token. JS sets `__ymAudioOnly` and rewrites
`enabled_track_types_bitfield = 1` (and DRC). Real googlevideo then sends
**no video**. Do not invent a video UMP tail — that is a new stream on the
leftover IY (kills `br=`, `Si=-`, seek 0).

Product `"zero"` pins YouTube quality `"tiny"` (144p) on **VOD** so ABR
binds a real ladder itag. Live must **not** pin tiny: that drops leftover
IY (often 243) from `vid=`.

Audio-only surface (MSE + HTTP only — no `base.js` internals):

1. SABR request field `40=1` (and DRC). Cache must not serve leftover video.
2. Dummy SourceBuffer `buffered` so drain’s MSE intersection is non-empty.
   Non-audio appends never reach C. `dropVideoTrack` wipes leftover.

Do not forge request `selected_format_ids` / `buffered_ranges`. Do not write
YouTube player fields (`Cx`, `Ds.L`, …).
