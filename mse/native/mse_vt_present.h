#ifndef YMUSIC_MSE_VT_PRESENT_H
#define YMUSIC_MSE_VT_PRESENT_H

#ifdef __APPLE__

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* JVM: overlay on an AWT Canvas via JAWT SurfaceLayers. iOS unused. */
void *mse_vt_attach_component(void *env, void *component);
/* Last JAWT-retained layer; detach CFReleases if it matches. */
extern void *mse_vt_jawt_layer;
void mse_vt_jawt_unhost(void);
/* Canvas screen rect (AWT y-down). Async; does not hop the AWT thread. */
void mse_vt_layout_screen(int x, int y, int w, int h);

/* Borrowed AVSampleBufferDisplayLayer. Caller owns the layer. */
void mse_vt_detach(void *layer);
int mse_vt_show(void *layer, void *cv_pixel_buffer, int w, int h);
int mse_vt_show_bgra(void *layer, const uint8_t *bgra, int w, int h);
/* Software I420/NV12 → overlay. Layer ResizeAspect; no CPU BGRA. cv_pix_fmt is
   kCVPixelFormatType_420YpCbCr8Planar or BiPlanarVideoRange. */
int mse_vt_show_sw(void *layer, unsigned cv_pix_fmt,
                   const uint8_t *const *data, const int *linesize, int w, int h);
void mse_vt_flush(void *layer);

/* H.264 / VP9 VTDecompressionSession. Enable HW, do not Require it
   (simulator / Intel use Apple software VT). NULL if create fails.
   ffmpeg's videotoolbox hwaccel wraps native `vp9` (SIGILL); this is
   Apple's session, the Chrome path. */
void *mse_vt_dec_open(const uint8_t *avcc, int avcc_n, int w, int h);
void *mse_vt_dec_open_vp9(const uint8_t *vpcc, int vpcc_n, int w, int h);
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
