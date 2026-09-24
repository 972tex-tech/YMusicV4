#ifndef YMUSIC_MSE_NATIVE_H
#define YMUSIC_MSE_NATIVE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* KN cinterop surface. Same C host as JNI MseHostNative.create/vtable/frames/copyFrame. */
void *mse_host_create(void);
void mse_host_destroy(void *h);
const void *mse_host_vtable(void);
int mse_host_frames(void *h);
int mse_host_video_size(void *h, int *w, int *ht);
int64_t mse_host_samples_played(void *h);
int mse_host_copy_frame(void *h, uint8_t *dst, int cap, int *w, int *ht, int *gen);
void mse_host_set_video_surface(void *h, void *window);
void mse_ios_recover_playback(void);
void mse_ios_park_video(void);
/* Unified log on iOS (os_log); stderr elsewhere. Safe for any message text. */
void mse_ios_log(const char *msg);

#ifdef __cplusplus
}
#endif

#endif
