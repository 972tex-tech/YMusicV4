#ifndef YMUSIC_FILE_AV_H
#define YMUSIC_FILE_AV_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* In-process libav file player. Separate from leftover MSE (no append/seek window). */

typedef struct FileAv FileAv;

typedef void (*FileAvFrameFn)(void *user, int w, int h, const uint8_t *bgra);
typedef void (*FileAvPcmFn)(void *user, int rate, int ch, const int16_t *s16, int nbytes);
typedef void (*FileAvProgressFn)(void *user, double pos, double dur, int w, int h);
typedef void (*FileAvEventFn)(void *user, int ended, const char *err);

FileAv *file_av_create(void);
void file_av_destroy(FileAv *p);

int file_av_open(FileAv *p, const char *path);
void file_av_close(FileAv *p);

void file_av_play(FileAv *p);
void file_av_pause(FileAv *p);
void file_av_seek(FileAv *p, double seconds);
void file_av_set_speed(FileAv *p, double speed);
void file_av_set_audio_index(FileAv *p, int stream_index);
void file_av_set_video_enabled(FileAv *p, int on);
/* Downscale so max(w,h) <= max_edge. 0 = 1920. Never upscales. */
void file_av_set_blit_max(FileAv *p, int max_edge);

double file_av_duration(FileAv *p);
double file_av_position(FileAv *p);
int file_av_ended(FileAv *p);
int file_av_video_size(FileAv *p, int *w, int *h);
const char *file_av_last_error(FileAv *p);

int file_av_stream_count(FileAv *p);
/* kind 0=audio 1=video 2=text. lang/codec are 0-terminated; may be empty. */
int file_av_stream_at(FileAv *p, int i, int *kind, int *index, int *w, int *h,
                      char *lang, int lang_n, char *codec, int codec_n);

void file_av_set_sinks(FileAv *p, FileAvFrameFn on_frame, FileAvPcmFn on_pcm,
                       FileAvProgressFn on_prog, FileAvEventFn on_event, void *user);
void *file_av_user(FileAv *p);

#ifdef __cplusplus
}
#endif

#endif
