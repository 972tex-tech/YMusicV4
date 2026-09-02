#ifndef YMUSIC_MSE_FFMPEG_H
#define YMUSIC_MSE_FFMPEG_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

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
void mse_av_seek(MseAv *a, double seconds);

/* 1 = frame, 0 = none yet, -1 = error. Caller free()s *bgra. */
int mse_av_pull_video(MseAv *a, double clock, uint8_t **bgra, int *w, int *h, double *pts);
/* 1 = pcm, 0 = none. *s16 is interleaved S16, caller free()s. */
int mse_av_pull_audio(MseAv *a, double clock, int16_t **s16, int *frames, int *rate, int *ch, double *pts);

double mse_av_duration(MseAv *a);

/* First..last PTS across leftover. 1 if any packets. */
int mse_av_span(MseAv *a, double *first, double *last);

/* Writes up to [max_pairs] [start,end] pairs. Returns pair count. */
int mse_av_buffered(MseAv *a, int audio, double *pairs, int max_pairs);

#ifdef __cplusplus
}
#endif

#endif
