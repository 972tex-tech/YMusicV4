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

/* H.264 VTDecompressionSession. Enable HW, do not Require it (simulator
   uses Apple software VT). NULL if create fails. */
void *mse_vt_dec_open(const uint8_t *avcc, int avcc_n, int w, int h);
void mse_vt_dec_close(void *dec);
void mse_vt_dec_flush(void *dec);
int mse_vt_dec_send(void *dec, const uint8_t *data, int n, int output);
/* 1 = pixel buffer in *cvpb (caller CVPixelBufferRelease), 0 = none. */
int mse_vt_dec_recv(void *dec, void **cvpb, int *w, int *h);
void mse_vt_pb_release(void *cvpb);

#ifdef __cplusplus
}
#endif

#endif

#endif
