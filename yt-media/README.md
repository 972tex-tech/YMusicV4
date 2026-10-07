# :yt-media

Pure Kotlin (commonMain) implementation of the googlevideo SABR/UMP protocol and the download
logic built on it — ported from the legacy app.

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
- `proxy/` — player media proxy: cache HIT/tee + audio-only dummy video tail; engine-js sends the adaptiveFormats catalog as base64 JSON `X-Ym-Af` (stripped before googlevideo)

SABR request/response loop, UMP parts, VOD vs live, and audio-only dummy rules:
[`SABR.md`](SABR.md).
