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
    /* Compose SwingPanel hosts a JPanel. JAWT GetDrawingSurfaceInfo on that
       peer SIGSEGVs in awt_DrawingSurface_GetDrawingSurfaceInfo (playground
       hs_err_pid13687, AWT-EventQueue-0). Keep CPU blit until we have a
       real Canvas/NSView, not this interop hole. */
    (void)env;
    (void)component;
    return NULL;
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
