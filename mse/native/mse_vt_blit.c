#ifdef __APPLE__

#include "mse_vt_blit.h"

#include <CoreMedia/CoreMedia.h>
#include <CoreVideo/CoreVideo.h>
#include <VideoToolbox/VideoToolbox.h>
#include <stdint.h>
#include <string.h>

static int blit_fit(int src_w, int src_h, int hole_w, int hole_h, int *dw, int *dh)
{
    if (src_w <= 0 || src_h <= 0)
        return -1;
    if (hole_w <= 0 || hole_h <= 0 || (src_w <= hole_w && src_h <= hole_h)) {
        *dw = src_w;
        *dh = src_h;
        return 0;
    }
    if ((int64_t)src_w * hole_h <= (int64_t)src_h * hole_w) {
        *dh = hole_h;
        *dw = (int)((int64_t)src_w * hole_h / src_h);
    } else {
        *dw = hole_w;
        *dh = (int)((int64_t)src_h * hole_w / src_w);
    }
    if (*dw < 1)
        *dw = 1;
    if (*dh < 1)
        *dh = 1;
    return 0;
}

void mse_vt_blit_free(MseVtBlit *s)
{
    if (!s)
        return;
    if (s->xfer) {
        VTPixelTransferSessionInvalidate((VTPixelTransferSessionRef)s->xfer);
        CFRelease(s->xfer);
        s->xfer = NULL;
    }
    if (s->dst) {
        CVPixelBufferRelease((CVPixelBufferRef)s->dst);
        s->dst = NULL;
    }
    s->dw = s->dh = 0;
}

int mse_vt_blit_size(void *cv_pixel_buffer, int hole_w, int hole_h, int *w, int *h)
{
    CVPixelBufferRef src;
    int src_w, src_h;
    if (!cv_pixel_buffer || !w || !h)
        return -1;
    src = (CVPixelBufferRef)cv_pixel_buffer;
    src_w = (int)CVPixelBufferGetWidth(src);
    src_h = (int)CVPixelBufferGetHeight(src);
    return blit_fit(src_w, src_h, hole_w, hole_h, w, h);
}

int mse_vt_blit_copy(MseVtBlit *s, void *cv_pixel_buffer, uint8_t *out, int w, int h)
{
    CVPixelBufferRef src, dst;
    VTPixelTransferSessionRef xfer;
    uint8_t *base;
    size_t stride;
    int y;
    if (!s || !cv_pixel_buffer || !out || w <= 0 || h <= 0)
        return -1;
    src = (CVPixelBufferRef)cv_pixel_buffer;
    CVBufferSetAttachment(src, kCVImageBufferYCbCrMatrixKey,
                          kCVImageBufferYCbCrMatrix_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
    CVBufferSetAttachment(src, kCVImageBufferColorPrimariesKey,
                          kCVImageBufferColorPrimaries_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
    CVBufferSetAttachment(src, kCVImageBufferTransferFunctionKey,
                          kCVImageBufferTransferFunction_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
    CVBufferSetAttachment(src, kCMFormatDescriptionExtension_FullRangeVideo,
                          kCFBooleanFalse, kCVAttachmentMode_ShouldPropagate);
    if (!s->xfer) {
        if (VTPixelTransferSessionCreate(NULL, &xfer) != noErr)
            return -1;
        s->xfer = xfer;
        VTSessionSetProperty(xfer, kVTPixelTransferPropertyKey_DestinationColorPrimaries,
                             kCVImageBufferColorPrimaries_ITU_R_709_2);
        VTSessionSetProperty(xfer, kVTPixelTransferPropertyKey_DestinationTransferFunction,
                             kCVImageBufferTransferFunction_ITU_R_709_2);
        VTSessionSetProperty(xfer, kVTPixelTransferPropertyKey_DestinationYCbCrMatrix,
                             kCVImageBufferYCbCrMatrix_ITU_R_709_2);
        VTSessionSetProperty(xfer, kVTPixelTransferPropertyKey_RealTime, kCFBooleanTrue);
    }
    xfer = (VTPixelTransferSessionRef)s->xfer;
    if (!s->dst || s->dw != w || s->dh != h) {
        if (s->dst)
            CVPixelBufferRelease((CVPixelBufferRef)s->dst);
        s->dst = NULL;
        if (CVPixelBufferCreate(kCFAllocatorDefault, (size_t)w, (size_t)h,
                                kCVPixelFormatType_32BGRA, NULL, &dst) != kCVReturnSuccess)
            return -1;
        s->dst = dst;
        s->dw = w;
        s->dh = h;
        CVBufferSetAttachment(dst, kCVImageBufferColorPrimariesKey,
                              kCVImageBufferColorPrimaries_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
        CVBufferSetAttachment(dst, kCVImageBufferTransferFunctionKey,
                              kCVImageBufferTransferFunction_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
        CVBufferSetAttachment(dst, kCVImageBufferYCbCrMatrixKey,
                              kCVImageBufferYCbCrMatrix_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
    }
    dst = (CVPixelBufferRef)s->dst;
    if (VTPixelTransferSessionTransferImage(xfer, src, dst) != noErr)
        return -1;
    if (CVPixelBufferLockBaseAddress(dst, kCVPixelBufferLock_ReadOnly) != kCVReturnSuccess)
        return -1;
    base = CVPixelBufferGetBaseAddress(dst);
    stride = CVPixelBufferGetBytesPerRow(dst);
    for (y = 0; y < h; y++)
        memcpy(out + (size_t)y * (size_t)w * 4, base + (size_t)y * stride, (size_t)w * 4);
    CVPixelBufferUnlockBaseAddress(dst, kCVPixelBufferLock_ReadOnly);
    return 0;
}

#endif
