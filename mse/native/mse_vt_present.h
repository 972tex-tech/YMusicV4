#ifndef YMUSIC_MSE_VT_PRESENT_H
#define YMUSIC_MSE_VT_PRESENT_H

#ifdef __APPLE__

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* JVM: overlay on an AWT component. iOS unused (Kotlin owns the layer). */
void *mse_vt_attach_component(void *env, void *component);

/* Borrowed AVSampleBufferDisplayLayer. Caller owns the layer. */
void mse_vt_detach(void *layer);
int mse_vt_show(void *layer, void *cv_pixel_buffer, int w, int h);
int mse_vt_show_bgra(void *layer, const uint8_t *bgra, int w, int h);
void mse_vt_flush(void *layer);

#ifdef __cplusplus
}
#endif

#endif

#endif
