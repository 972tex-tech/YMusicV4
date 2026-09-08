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
void mse_av_flush(MseAv *a, int audio, int video);
void mse_av_abort(MseAv *a, int audio);
/* MSE changeType: reset parser + drop init for that track; leftover stays. */
void mse_av_change_type(MseAv *a, int audio);
/* Live audio-only: keep appending video leftover, do not decode. */
void mse_av_rest_video(MseAv *a, int on);
void mse_av_seek(MseAv *a, double seconds);

/* Android: borrowed ANativeWindow. Apple: borrowed AVSampleBufferDisplayLayer.
 * NULL detaches. No-op on other hosts. */
void mse_av_set_window(MseAv *a, void *window);

/* 1 = frame, 0 = none yet, -1 = error. Caller free()s *bgra when non-NULL.
 * Android MediaCodec / Apple VT layer return 1 with *bgra == NULL (already shown). */
int mse_av_pull_video(MseAv *a, double clock, uint8_t **bgra, int *w, int *h, double *pts);
/* 1 = pcm, 0 = none. *s16 is interleaved S16, caller free()s. */
int mse_av_pull_audio(MseAv *a, double clock, int16_t **s16, int *frames, int *rate, int *ch, double *pts);

double mse_av_duration(MseAv *a);

/* First..last PTS across leftover. 1 if any packets. */
int mse_av_span(MseAv *a, double *first, double *last);

/* Unsent packets remain (rd < n). */
int mse_av_video_pending(MseAv *a);
/* 1 if pull can send or present now (not waiting on a future pts). */
int mse_av_video_due(MseAv *a, double clock);
int mse_av_audio_pending(MseAv *a);
int mse_av_has_video(MseAv *a);

/* Writes up to [max_pairs] [start,end] pairs. Returns pair count. */
int mse_av_buffered(MseAv *a, int audio, double *pairs, int max_pairs);

#ifdef __cplusplus
}
#endif

#endif
