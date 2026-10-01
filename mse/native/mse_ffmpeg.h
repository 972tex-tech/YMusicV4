#ifndef YMUSIC_MSE_FFMPEG_H
#define YMUSIC_MSE_FFMPEG_H

#include <stddef.h>
#include <stdint.h>

/* Protocol traces (append/seek/decoder). 0 in shipped builds; cmake -DMSE_DEBUG=ON
   or -DMSE_DEBUG=1 matches engine-js MSE_DEBUG. */
#ifndef MSE_DEBUG
#define MSE_DEBUG 0
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* HH:mm:ss.SSS on stderr. C writes bypass Java System.err. */
void mse_logf(const char *fmt, ...);

typedef struct MseAv MseAv;

typedef struct {
    int n;
    int is_init;
    double first;
    double last;
} MseAvIngest;

MseAv *mse_av_create(void);
void mse_av_destroy(MseAv *a);

MseAvIngest mse_av_append(MseAv *a, int audio, const uint8_t *p, int n, double ts_off,
                          double win_start, double win_end);
void mse_av_remove(MseAv *a, int audio, double start, double end);
size_t mse_av_leftover_bytes(MseAv *a, int audio);
/* Drop coded frames with pts+dur <= before until `need` bytes are freed (Chrome MSE GC). */
void mse_av_evict_before(MseAv *a, int audio, double before, size_t need);
void mse_av_flush(MseAv *a, int audio, int video);
void mse_av_abort(MseAv *a, int audio);
/* MSE changeType: reset parser + drop init for that track; leftover stays. */
void mse_av_change_type(MseAv *a, int audio);
/* Live audio-only: keep appending video leftover, do not decode. */
void mse_av_rest_video(MseAv *a, int on, double clock);
int mse_av_video_resting(const MseAv *a);
#ifdef __APPLE__
void mse_av_park_video(MseAv *a);
int mse_av_video_parked(const MseAv *a);
#endif
void mse_av_seek(MseAv *a, double seconds);

/* Android: borrowed ANativeWindow. Apple: borrowed AVSampleBufferDisplayLayer.
 * NULL detaches. No-op on other hosts. */
void mse_av_set_window(MseAv *a, void *window);
#ifdef __APPLE__
/* Foreground after iOS suspend: catch up from last RAP still in leftover. */
void mse_av_recover_display(MseAv *a, double clock);
#endif

/* JVM desktop blit hole in pixels. 0×0 = decode size. Downscale-only min-fit
 * (never upscale; dest is the fitted picture, not a letterboxed panel). */
void mse_av_set_blit_size(MseAv *a, int w, int h);

/* 1 = frame, 0 = none yet, -1 = error. *bgra is borrowed from MseAv (do not
 * free). Android MediaCodec / Apple VT layer return 1 with *bgra == NULL. */
int mse_av_pull_video(MseAv *a, double clock, uint8_t **bgra, int *w, int *h, double *pts);
/* 1 = pcm, 0 = none. *s16 is interleaved S16, caller free()s. */
int mse_av_pull_audio(MseAv *a, double clock, int16_t **s16, int *frames, int *rate, int *ch, double *pts);

double mse_av_duration(MseAv *a);

/* First..last PTS across leftover. 1 if any packets. */
int mse_av_span(MseAv *a, double *first, double *last);

/* 1 when leftover had samples and every AU is consumed (rd >= n). Mid-play
 * SABR starve with an empty unread queue also returns 1 — the host still
 * requires last ≈ MediaSource.duration (or endOfStream) before HTML ended. */
int mse_av_drained(MseAv *a);

/* Unsent packets remain (rd < n). */
int mse_av_video_pending(MseAv *a);
/* 1 if pull can send or present now (not waiting on a future pts). 0 if v_rest. */
int mse_av_video_due(MseAv *a, double clock);
int mse_av_audio_pending(MseAv *a);
int mse_av_has_video(MseAv *a);
/* 1 if __mseRestVideo: decoder asleep (audio-only / product-zero). */
int mse_av_video_rested(MseAv *a);

/* Writes up to [max_pairs] [start,end] pairs. Returns pair count. */
int mse_av_buffered(MseAv *a, int audio, double *pairs, int max_pairs);

#ifdef __cplusplus
}
#endif

#endif
