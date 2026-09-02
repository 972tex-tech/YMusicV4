#ifndef YMUSIC_MSE_HOST_H
#define YMUSIC_MSE_HOST_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MseHost MseHost;

typedef void (*MseFrameFn)(void *user, int w, int h, const uint8_t *bgra);
typedef void (*MsePcmFn)(void *user, int rate, int ch, const int16_t *s16, int nbytes);
typedef void (*MseProgressFn)(void *user, double pos, double dur, int w, int h);

MseHost *mse_host_create(void);
void mse_host_destroy(MseHost *h);
/* Function table for QuickJS `__mse*` trampolines. */
const void *mse_host_vtable(void);
void mse_host_set_sinks(MseHost *h, MseFrameFn on_frame, MsePcmFn on_pcm, MseProgressFn on_prog, void *user);
/* JNI/app: the only host call. JS drives play/pause/currentTime via C trampolines. */
void mse_host_set_video_surface(MseHost *h, void *window);

void mse_host_play(MseHost *h);
void mse_host_pause(MseHost *h);
void mse_host_reset(MseHost *h);

int mse_host_paused(MseHost *h);
int mse_host_ready_state(MseHost *h);
double mse_host_current_time(MseHost *h);
void mse_host_set_current_time(MseHost *h, double seconds);
double mse_host_duration(MseHost *h);
void mse_host_set_duration(MseHost *h, double seconds);
int mse_host_video_size(MseHost *h, int *w, int *ht);
int mse_host_frames(MseHost *h);
/* Last decoded BGRA. dst=NULL or cap too small → returns bytes needed (w/h/gen still set). 0 = none. */
int mse_host_copy_frame(MseHost *h, uint8_t *dst, int cap, int *w, int *ht, int *gen);

int mse_host_append(MseHost *h, int audio, const uint8_t *p, int n, double ts_off, double win_start, double win_end);
void mse_host_remove(MseHost *h, int audio, double start, double end);
void mse_host_abort(MseHost *h, int audio);
int mse_host_buffered(MseHost *h, int audio, double *pairs, int max_pairs);

#ifdef __cplusplus
}
#endif

#endif
