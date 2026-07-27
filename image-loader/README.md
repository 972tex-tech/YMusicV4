# `:image-loader`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

Our own simple Compose image loader (jvm + android + compose-iOS targets).

- **ImageLoader** — `ImageLoader(fetchers, scaledCache?, …)`: **everything is a fetcher**
  (`ImageFetcher.fetch(url, cacheKey, size): DecodedImage?`, first non-null wins).

- **Fetchers**:
  - `networkFetcher(client, fs, cacheDir)` = the Coil/Glide model on kmpHttp's `DiskLruCache` (body streams to disk
    under a byte cap, decode **from the file**, cacheKey-keyed so all sizes share one download; the client carries
    NO http cache).
  - `fileFetcher()` = `file://`.
  - Custom schemes (ffmpeg album art, content providers) plug in with the public `decodeScaled`/`decodeScaledFile`
    helpers.

- **Scaled decode + caches** — decodes **scaled to the view size** (Android subsampled `BitmapFactory`, Skia
  draw-down on desktop/iOS via a shared `skikoMain` source set — the full-size image never stays resident),
  memory-LRUs decoded bitmaps, and optionally persists the scaled PNG (`ScaledImageCache` / `DiskScaledImageCache`
  on okio) so warm starts skip fetch + full decode.

- **Concurrency** — a **LIFO gate** (newest request first, cancellation frees the slot/stack — scroll-away cancels
  via composition, scroll-in preempts).

- **AsyncImage** — `AsyncImage(loader, url, …, placeholder?, error?)` measures itself and loads at its own pixel
  size (64px-bucketed cache keys).
  - Shows `placeholder` while loading and `error` (→ placeholder fallback) on failure.
  - Draws a memory-cached image on its FIRST frame via a synchronous `peek` (no placeholder flash on scroll-back)
    (logged breadcrumb, never thrown into composition).
  - A resize reloads only on ≥100px growth after a 400ms settle — never per drag frame.
