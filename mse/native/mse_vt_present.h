#ifndef YMUSIC_MSE_VT_PRESENT_H
#define YMUSIC_MSE_VT_PRESENT_H

#ifdef __APPLE__
#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Overlay AVSampleBufferDisplayLayer on an AWT component via JAWT_SurfaceLayers. */
void *mse_vt_attach_component(JNIEnv *env, jobject component);
void mse_vt_detach(void *layer);
int mse_vt_show(void *layer, void *cv_pixel_buffer, int w, int h);
void mse_vt_flush(void *layer);

#ifdef __cplusplus
}
#endif
#endif

#endif
