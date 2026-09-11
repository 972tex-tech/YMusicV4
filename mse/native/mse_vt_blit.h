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

/* Fit cvpb into hole (0×0 = native). */
int mse_vt_blit_size(void *cv_pixel_buffer, int hole_w, int hole_h, int *w, int *h);

/* 709 TV-range → packed BGRA of size w×h (caller owns dst, w*h*4 bytes). */
int mse_vt_blit_copy(MseVtBlit *s, void *cv_pixel_buffer, uint8_t *dst, int w, int h);

#ifdef __cplusplus
}
#endif

#endif
#endif
