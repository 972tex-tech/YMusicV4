#ifdef __APPLE__
#import "mse_vt_present.h"
#import <AVFoundation/AVFoundation.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <QuartzCore/QuartzCore.h>
#import <jawt.h>
#import <jawt_md.h>

void *mse_vt_attach_component(JNIEnv *env, jobject component)
{
    JAWT awt;
    JAWT_DrawingSurface *ds;
    JAWT_DrawingSurfaceInfo *dsi;
    jint lock;
    AVSampleBufferDisplayLayer *layer;
    id<JAWT_SurfaceLayers> layers;

    if (!env || !component)
        return NULL;
    awt.version = JAWT_VERSION_1_7;
    if (JAWT_GetAWT(env, &awt) == JNI_FALSE)
        return NULL;
    ds = awt.GetDrawingSurface(env, component);
    if (!ds)
        return NULL;
    lock = ds->Lock(ds);
    if ((lock & JAWT_LOCK_ERROR) != 0) {
        awt.FreeDrawingSurface(ds);
        return NULL;
    }
    dsi = ds->GetDrawingSurfaceInfo(ds);
    if (!dsi || !dsi->platformInfo) {
        ds->Unlock(ds);
        awt.FreeDrawingSurface(ds);
        return NULL;
    }
    layers = (__bridge id<JAWT_SurfaceLayers>)dsi->platformInfo;
    layer = [AVSampleBufferDisplayLayer layer];
    layer.videoGravity = AVLayerVideoGravityResizeAspect;
    layer.opaque = NO;
    layers.layer = layer;
    ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    return (void *)CFBridgingRetain(layer);
}

void mse_vt_detach(void *p)
{
    AVSampleBufferDisplayLayer *layer;
    if (!p)
        return;
    layer = CFBridgingRelease(p);
    [layer flushAndRemoveImage];
    layer.hidden = YES;
    [layer removeFromSuperlayer];
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
    if (layer.status == AVQueuedSampleBufferRenderingStatusFailed)
        [layer flush];
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

void mse_vt_flush(void *p)
{
    AVSampleBufferDisplayLayer *layer;
    if (!p)
        return;
    layer = (__bridge AVSampleBufferDisplayLayer *)p;
    [layer flushAndRemoveImage];
}
#endif
