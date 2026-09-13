#ifdef __APPLE__
#import "mse_vt_present.h"
#import "mse_ffmpeg.h"
#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <os/log.h>
#import <string.h>

void mse_vt_detach(void *p)
{
    AVSampleBufferDisplayLayer *layer;
    if (!p)
        return;
    layer = (__bridge AVSampleBufferDisplayLayer *)p;
    [layer flushAndRemoveImage];
    if (p == mse_vt_jawt_layer) {
        mse_vt_jawt_layer = NULL;
        mse_vt_jawt_unhost();
        CFRelease(p);
    }
}

int mse_vt_show(void *p, void *cv_pixel_buffer, int w, int h)
{
    AVSampleBufferDisplayLayer *layer;
    CVPixelBufferRef pb = (CVPixelBufferRef)cv_pixel_buffer;
    CMVideoFormatDescriptionRef fmt = NULL;
    CMSampleBufferRef sbuf = NULL;
    CMSampleTimingInfo timing = kCMTimingInfoInvalid;
    OSStatus st;
    CFArrayRef atts;
    if (!p || !pb)
        return -1;
    layer = (__bridge AVSampleBufferDisplayLayer *)p;
    if (layer.status == AVQueuedSampleBufferRenderingStatusFailed) {
        NSError *err = layer.error;
        os_log(os_log_create("com.ymusic.mse", "v"),
               "mse-v vt-show layer-failed %{public}s",
               err.localizedDescription.UTF8String ?: "?");
        [layer flush];
    }
    st = CMVideoFormatDescriptionCreateForImageBuffer(kCFAllocatorDefault, pb, &fmt);
    if (st != noErr || !fmt)
        return -1;
    st = CMSampleBufferCreateReadyWithImageBuffer(kCFAllocatorDefault, pb, fmt, &timing, &sbuf);
    CFRelease(fmt);
    if (st != noErr || !sbuf)
        return -1;
    atts = CMSampleBufferGetSampleAttachmentsArray(sbuf, YES);
    if (atts && CFArrayGetCount(atts) > 0) {
        CFMutableDictionaryRef d = (CFMutableDictionaryRef)CFArrayGetValueAtIndex(atts, 0);
        CFDictionarySetValue(d, kCMSampleAttachmentKey_DisplayImmediately, kCFBooleanTrue);
    }
    if (@available(macOS 14.0, *))
        [layer.sampleBufferRenderer enqueueSampleBuffer:sbuf];
    else
        [layer enqueueSampleBuffer:sbuf];
    CFRelease(sbuf);
    {
        static int shown;
        if (shown < 3) {
            shown++;
            mse_logf("mse-v vt-show %dx%d layer=%.0fx%.0f status=%d\n",
                     w, h, layer.bounds.size.width, layer.bounds.size.height, (int)layer.status);
        }
    }
    return 0;
}

int mse_vt_show_bgra(void *p, const uint8_t *bgra, int w, int h)
{
    CVPixelBufferRef pb = NULL;
    uint8_t *dst;
    size_t stride;
    int y, r;
    if (!p || !bgra || w <= 0 || h <= 0)
        return -1;
    if (CVPixelBufferCreate(kCFAllocatorDefault, (size_t)w, (size_t)h,
                            kCVPixelFormatType_32BGRA, NULL, &pb) != kCVReturnSuccess ||
        !pb)
        return -1;
    CVPixelBufferLockBaseAddress(pb, 0);
    dst = CVPixelBufferGetBaseAddress(pb);
    stride = CVPixelBufferGetBytesPerRow(pb);
    if (!dst) {
        CVPixelBufferUnlockBaseAddress(pb, 0);
        CVPixelBufferRelease(pb);
        return -1;
    }
    for (y = 0; y < h; y++)
        memcpy(dst + (size_t)y * stride, bgra + (size_t)y * (size_t)w * 4, (size_t)w * 4);
    CVPixelBufferUnlockBaseAddress(pb, 0);
    r = mse_vt_show(p, pb, w, h);
    CVPixelBufferRelease(pb);
    return r;
}

int mse_vt_show_sw(void *p, unsigned cv_pix_fmt,
                   const uint8_t *const *data, const int *linesize, int w, int h)
{
    CVPixelBufferRef pb = NULL;
    size_t nplane, i, y;
    int r;
    if (!p || !data || !linesize || w <= 0 || h <= 0)
        return -1;
    if (CVPixelBufferCreate(kCFAllocatorDefault, (size_t)w, (size_t)h,
                            (OSType)cv_pix_fmt, NULL, &pb) != kCVReturnSuccess ||
        !pb)
        return -1;
    CVBufferSetAttachment(pb, kCVImageBufferYCbCrMatrixKey,
                          kCVImageBufferYCbCrMatrix_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
    CVBufferSetAttachment(pb, kCVImageBufferColorPrimariesKey,
                          kCVImageBufferColorPrimaries_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
    CVBufferSetAttachment(pb, kCVImageBufferTransferFunctionKey,
                          kCVImageBufferTransferFunction_ITU_R_709_2, kCVAttachmentMode_ShouldPropagate);
    CVPixelBufferLockBaseAddress(pb, 0);
    nplane = CVPixelBufferGetPlaneCount(pb);
    if (nplane < 2 || nplane > 3) {
        CVPixelBufferUnlockBaseAddress(pb, 0);
        CVPixelBufferRelease(pb);
        return -1;
    }
    for (i = 0; i < nplane; i++) {
        uint8_t *dst = CVPixelBufferGetBaseAddressOfPlane(pb, i);
        size_t dst_str = CVPixelBufferGetBytesPerRowOfPlane(pb, i);
        size_t rows = CVPixelBufferGetHeightOfPlane(pb, i);
        size_t pw = CVPixelBufferGetWidthOfPlane(pb, i);
        const uint8_t *src = data[i];
        int src_str = linesize[i];
        size_t rowb, bpp;
        if (!dst || !src || src_str <= 0) {
            CVPixelBufferUnlockBaseAddress(pb, 0);
            CVPixelBufferRelease(pb);
            return -1;
        }
        /* NV12 chroma plane is 2 bytes per sample; I420 planes are 1. */
        bpp = (nplane == 2 && i == 1) ? 2 : 1;
        rowb = pw * bpp;
        if (rowb > (size_t)src_str)
            rowb = (size_t)src_str;
        if (rowb > dst_str)
            rowb = dst_str;
        for (y = 0; y < rows; y++)
            memcpy(dst + y * dst_str, src + y * (size_t)src_str, rowb);
    }
    CVPixelBufferUnlockBaseAddress(pb, 0);
    r = mse_vt_show(p, pb, w, h);
    CVPixelBufferRelease(pb);
    return r;
}

void mse_vt_flush(void *p)
{
    AVSampleBufferDisplayLayer *layer;
    if (!p)
        return;
    layer = (__bridge AVSampleBufferDisplayLayer *)p;
    [layer flushAndRemoveImage];
}
#endif
