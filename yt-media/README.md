# :yt-media

Pure Kotlin (commonMain) implementation of the googlevideo SABR/UMP protocol and the download
logic built on it — ported from the legacy app (see `docs/player/sabr-protocol.md` for the
protocol field guide and the legacy file map).

**Boundary rule:** this module knows nothing about the WebView, the JS engines, or the player.
Everything the protocol needs per video — the SABR streaming URL (sig/n **already decoded**),
`videoPlaybackUstreamerConfig`, PO token, client info, adaptive formats — is fed in by the caller
as plain data (`SabrStreamSpec`). In the app that data comes from the webview player surface;
in tests it comes from fixtures. HTTP goes through the app's standard `KmpHttpClient` (`:kmpHttp:core`):
the caller configures it (cookie jar, timeouts) and passes it to `SabrSession.download()` with the
matching User-Agent, so the SABR requests share the WebView session's cookie/UA identity.

Layers (bottom-up):
- `proto/` — minimal protobuf wire reader/writer (varint, fixed32/64, length-delimited)
- `ump/` — UMP framing: part ids + the UMP varint codec (NOT protobuf varints)
- `sabr/` — SABR request/response protobuf models + `SabrStreamSpec` input params

Planned next: the session loop (`SabrSession`), segment index + byte-range cache, and the two
sinks (sequential download file, player cache).
