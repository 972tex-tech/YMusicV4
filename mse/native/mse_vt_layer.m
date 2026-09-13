#ifdef __APPLE__
#import "mse_vt_present.h"
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
    (void)w;
    (void)h;
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
    [layer enqueueSampleBuffer:sbuf];
    CFRelease(sbuf);
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

void mse_vt_flush(void *p)
{
    AVSampleBufferDisplayLayer *layer;
    if (!p)
        return;
    layer = (__bridge AVSampleBufferDisplayLayer *)p;
    [layer flushAndRemoveImage];
}
#endif
