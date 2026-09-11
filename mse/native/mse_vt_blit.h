#ifndef YMUSIC_MSE_VT_BLIT_H
#define YMUSIC_MSE_VT_BLIT_H

#ifdef __APPLE__

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MseVtBlit {
    void *xfer;
    void *dst;
    int dw, dh;
} MseVtBlit;

void mse_vt_blit_free(MseVtBlit *s);

/* Fit src into hole (0×0 = native). */
int mse_vt_blit_fit(int src_w, int src_h, int hole_w, int hole_h, int *w, int *h);

/* Fit cvpb into hole (0×0 = native). */
int mse_vt_blit_size(void *cv_pixel_buffer, int hole_w, int hole_h, int *w, int *h);

/* 709 TV-range → packed BGRA of size w×h (caller owns dst, w*h*4 bytes). */
int mse_vt_blit_copy(MseVtBlit *s, void *cv_pixel_buffer, uint8_t *dst, int w, int h);

/* kCVPixelFormatType_420YpCbCr8Planar / BiPlanarVideoRange. */
#define MSE_VT_BLIT_YUV420 0x79343230u
#define MSE_VT_BLIT_NV12   0x34323076u

/* Wrap software YUV (no copy) and GPU-scale like a VT frame. */
int mse_vt_blit_sw(MseVtBlit *s, unsigned cv_pix_fmt,
                   const uint8_t *const *data, const int *linesize,
                   int src_w, int src_h, uint8_t *dst, int w, int h);

#ifdef __cplusplus
}
#endif

#endif
#endif
