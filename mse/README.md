# `:mse`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

C Media Source host for the JS YouTube player. **Not** a Kotlin `MsePlayer` / MediaCodec path.

- **JS YouTube path** — leftover + ffmpeg demux + clock live in C (`mse_ffmpeg.c` / `mse_host.c`). QuickJS `__mse*` trampolines call that C directly. **JNI has one media call: `setVideoSurface`.** Seek is JS (`seekTo` → `HTMLMediaElement.currentTime` → `__mseSetCurrentTime`), not a host export. C→JS events: `MseJsEventBridge.fire` (any thread) → `DynamicEngine.fire` → `"event"` pipe → `ymix.onEvent`.
- **JVM desktop** — that C host; Compose preview blits bitmaps (JAWT on SwingPanel SIGSEGVs). Host JDK headers plus `ffmpeg-build/out/<desktop-slug>` shared libav (`FFMPEG_ROOT`) to build `libmseffmpegjni`. Same DSOs the packed `ffmpeg` CLI loads. Local files use a **separate** in-process host (`file_av.c` / `FileAvNative`), not leftover.
- **Android** — same C host; app passes a `Surface` (TextureView) into C. NDK build needs `FFMPEG_ROOT` (shared libav per ABI). OpenSL ES is the C audio DAC. Video is `AMediaCodec` into that Surface (HW if the SoC has it, Google software codec otherwise). libav + CPU blit is only if MediaCodec configure/start fails. Software volume/EQ/bass/virtualizer live in `audio_fx.c` on the PCM fill path; ExoPlayer local files JNI the same DSP via `AudioFxNative`.
- **Apple** — the same C in this module (`libmsehost.a` + cinterop) when `ffmpeg-build/out/ios-*` exists. `bindNativeMse` is `MseHostNative.create()` + `vtable()` into QuickJS, like JVM/Android.

Visual check: [`playground/README.md`](../playground/README.md) — JVM `:playground:run`, Android `:playground-android:installDebug`.
