#ifdef __APPLE__
#import "mse_vt_present.h"
#import "mse_ffmpeg.h"
#import <jni.h>
#import <jawt.h>
#import <jawt_md.h>
#import <AppKit/AppKit.h>
#import <AVFoundation/AVFoundation.h>
#import <QuartzCore/QuartzCore.h>
#import <dlfcn.h>
#import <string.h>

void *mse_vt_jawt_layer;
static NSView *mse_vt_jawt_view;
static id mse_end_live_obs;
static NSWindow *mse_live_win;
static int mse_pending_layout;
static int mse_px, mse_py, mse_pw, mse_ph;

@interface MseVtHostView : NSView
@end
@implementation MseVtHostView
- (NSView *)hitTest:(NSPoint)point
{
    (void)point;
    return nil;
}
@end

static NSRect mse_screen_to_view(NSView *parent, jint x, jint y, jint w, jint h);

static void mse_clear_live_obs(void)
{
    if (!mse_end_live_obs)
        return;
    [NSNotificationCenter.defaultCenter removeObserver:mse_end_live_obs];
    mse_end_live_obs = nil;
    mse_live_win = nil;
}

static void mse_apply_host_frame(int x, int y, int w, int h)
{
    NSView *host = mse_vt_jawt_view;
    NSView *parent = host.superview;
    NSRect frame;
    if (!host || !parent)
        return;
    frame = mse_screen_to_view(parent, x, y, w, h);
    if (NSEqualRects(host.frame, frame))
        return;
    host.frame = frame;
}

static jboolean (*mse_jawt_get_awt)(JNIEnv *, JAWT *);

static int jawt_bind(void)
{
    void *h;
    if (mse_jawt_get_awt)
        return 1;
    h = dlopen("libjawt.dylib", RTLD_LAZY | RTLD_GLOBAL);
    if (!h) {
        mse_logf("mse-v jawt dlopen failed %s\n", dlerror() ? dlerror() : "?");
        return 0;
    }
    mse_jawt_get_awt = (jboolean (*)(JNIEnv *, JAWT *))dlsym(h, "JAWT_GetAWT");
    if (!mse_jawt_get_awt) {
        mse_logf("mse-v jawt dlsym JAWT_GetAWT failed\n");
        return 0;
    }
    return 1;
}

/* Never dispatch_sync: fullscreen exit runs AppKit on main while AWT holds the
   resize/removeNotify, and a sync hop deadlocks the EDT. */
static void mse_on_main(void (^block)(void))
{
    if ([NSThread isMainThread])
        block();
    else
        dispatch_async(dispatch_get_main_queue(), block);
}

void mse_vt_jawt_unhost(void)
{
    NSView *v = mse_vt_jawt_view;
    mse_vt_jawt_view = nil;
    mse_pending_layout = 0;
    mse_clear_live_obs();
    if (!v)
        return;
    mse_on_main(^{
        [v removeFromSuperview];
    });
}

static NSView *mse_content_view(CALayer *wl)
{
    NSView *v = nil;
    CALayer *p;
    Class theme;
    if ([wl.delegate isKindOfClass:[NSView class]])
        v = (NSView *)wl.delegate;
    else {
        for (p = wl; p; p = p.superlayer) {
            if ([p.delegate isKindOfClass:[NSView class]]) {
                v = (NSView *)p.delegate;
                break;
            }
        }
    }
    if (!v)
        v = [NSApp keyWindow].contentView;
    theme = NSClassFromString(@"NSThemeFrame");
    if (theme && [v isKindOfClass:theme])
        v = v.window.contentView;
    if (v.window.contentView)
        v = v.window.contentView;
    return v;
}

static int mse_java_screen_rect(JNIEnv *env, jobject obj, jint *x, jint *y, jint *w, jint *h)
{
    jclass cls, ptcls;
    jmethodID getW, getH, getLoc;
    jobject pt;
    jfieldID fx, fy;

    cls = (*env)->GetObjectClass(env, obj);
    getW = (*env)->GetMethodID(env, cls, "getWidth", "()I");
    getH = (*env)->GetMethodID(env, cls, "getHeight", "()I");
    getLoc = (*env)->GetMethodID(env, cls, "getLocationOnScreen", "()Ljava/awt/Point;");
    if (!getW || !getH || !getLoc)
        return -1;
    *w = (*env)->CallIntMethod(env, obj, getW);
    *h = (*env)->CallIntMethod(env, obj, getH);
    pt = (*env)->CallObjectMethod(env, obj, getLoc);
    if ((*env)->ExceptionCheck(env) || !pt) {
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
        return -1;
    }
    ptcls = (*env)->GetObjectClass(env, pt);
    fx = (*env)->GetFieldID(env, ptcls, "x", "I");
    fy = (*env)->GetFieldID(env, ptcls, "y", "I");
    if (!fx || !fy)
        return -1;
    *x = (*env)->GetIntField(env, pt, fx);
    *y = (*env)->GetIntField(env, pt, fy);
    return (*w > 0 && *h > 0) ? 0 : -1;
}

static NSRect mse_screen_to_view(NSView *parent, jint x, jint y, jint w, jint h)
{
    NSRect screen, win;
    CGFloat top = NSMaxY(parent.window.screen.frame);

    screen = NSMakeRect((CGFloat)x, top - (CGFloat)y - (CGFloat)h, (CGFloat)w, (CGFloat)h);
    win = [parent.window convertRectFromScreen:screen];
    return [parent convertRect:win fromView:nil];
}

void mse_vt_layout_screen(int x, int y, int w, int h)
{
    if (w <= 0 || h <= 0)
        return;
    /* Always async: inlining on main from AWT doLayout set the NSView frame,
       which re-entered AWT layout (~300% CPU). Skip while the user is dragging
       the window edge — apply once on NSWindowDidEndLiveResize. */
    dispatch_async(dispatch_get_main_queue(), ^{
        NSView *host = mse_vt_jawt_view;
        NSWindow *win;
        if (!host)
            return;
        win = host.window;
        if (win && win.inLiveResize) {
            mse_px = x;
            mse_py = y;
            mse_pw = w;
            mse_ph = h;
            mse_pending_layout = 1;
            if (mse_end_live_obs && mse_live_win != win) {
                mse_clear_live_obs();
            }
            if (!mse_end_live_obs) {
                mse_live_win = win;
                mse_end_live_obs = [NSNotificationCenter.defaultCenter
                    addObserverForName:NSWindowDidEndLiveResizeNotification
                                object:win
                                 queue:nil
                            usingBlock:^(NSNotification *note) {
                                (void)note;
                                if (!mse_pending_layout)
                                    return;
                                mse_pending_layout = 0;
                                mse_apply_host_frame(mse_px, mse_py, mse_pw, mse_ph);
                            }];
            }
            return;
        }
        mse_pending_layout = 0;
        mse_apply_host_frame(x, y, w, h);
    });
}

static void *mse_vt_host_new(CALayer *wl, jint x, jint y, jint w, jint h)
{
    AVSampleBufferDisplayLayer *layer;
    void *retained;

    layer = [AVSampleBufferDisplayLayer layer];
    layer.videoGravity = AVLayerVideoGravityResizeAspect;
    layer.actions = @{ @"contents": [NSNull null], @"bounds": [NSNull null], @"frame": [NSNull null] };
    retained = (void *)CFBridgingRetain(layer);
    if (mse_vt_jawt_layer && mse_vt_jawt_layer != retained)
        mse_vt_detach(mse_vt_jawt_layer);
    mse_vt_jawt_layer = retained;
    mse_on_main(^{
        NSView *parent = mse_content_view(wl);
        NSView *host;
        NSRect frame;
        if (mse_vt_jawt_layer != retained)
            return;
        if (!parent) {
            mse_logf("mse-v jawt no content view\n");
            return;
        }
        frame = mse_screen_to_view(parent, x, y, w, h);
        host = [[MseVtHostView alloc] initWithFrame:frame];
        host.wantsLayer = YES;
        host.layer = (__bridge AVSampleBufferDisplayLayer *)retained;
        [parent addSubview:host];
        mse_vt_jawt_view = host;
        mse_logf("mse-v jawt layer attached %.0fx%.0f @ %.0f,%.0f parent=%s\n",
                 frame.size.width, frame.size.height, frame.origin.x, frame.origin.y,
                 parent.className.UTF8String ?: "?");
    });
    return retained;
}

void *mse_vt_attach_component(void *envp, void *component)
{
    JNIEnv *env = (JNIEnv *)envp;
    jobject obj = (jobject)component;
    jclass canvas;
    JAWT awt;
    JAWT_DrawingSurface *ds;
    JAWT_DrawingSurfaceInfo *dsi;
    id<JAWT_SurfaceLayers> sl;
    CALayer *wl;
    jint jx = 0, jy = 0, jw = 0, jh = 0;
    void *retained;

    if (!env || !obj)
        return NULL;
    if (mse_java_screen_rect(env, obj, &jx, &jy, &jw, &jh) != 0)
        return mse_vt_jawt_layer;
    if (mse_vt_jawt_layer && mse_vt_jawt_view) {
        mse_vt_layout_screen(jx, jy, jw, jh);
        return mse_vt_jawt_layer;
    }
    if (!jawt_bind())
        return NULL;
    canvas = (*env)->FindClass(env, "java/awt/Canvas");
    if (!canvas || !(*env)->IsInstanceOf(env, obj, canvas)) {
        mse_logf("mse-v jawt not a Canvas\n");
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
        return NULL;
    }
    memset(&awt, 0, sizeof(awt));
    awt.version = JAWT_VERSION_1_7;
    if (mse_jawt_get_awt(env, &awt) == JNI_FALSE) {
        mse_logf("mse-v jawt GetAWT failed\n");
        return NULL;
    }
    ds = awt.GetDrawingSurface(env, obj);
    if (!ds) {
        mse_logf("mse-v jawt GetDrawingSurface failed\n");
        return NULL;
    }
    if ((ds->Lock(ds) & JAWT_LOCK_ERROR) != 0) {
        mse_logf("mse-v jawt Lock failed\n");
        awt.FreeDrawingSurface(ds);
        return NULL;
    }
    dsi = ds->GetDrawingSurfaceInfo(ds);
    if (!dsi || !dsi->platformInfo) {
        mse_logf("mse-v jawt GetDrawingSurfaceInfo failed\n");
        ds->Unlock(ds);
        awt.FreeDrawingSurface(ds);
        return NULL;
    }
    sl = (__bridge id<JAWT_SurfaceLayers>)dsi->platformInfo;
    if (![(NSObject *)sl conformsToProtocol:@protocol(JAWT_SurfaceLayers)]) {
        mse_logf("mse-v jawt platformInfo is not SurfaceLayers\n");
        ds->FreeDrawingSurfaceInfo(dsi);
        ds->Unlock(ds);
        awt.FreeDrawingSurface(ds);
        return NULL;
    }
    wl = sl.windowLayer;
    ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    retained = mse_vt_host_new(wl, jx, jy, jw, jh);
    return retained;
}
#endif
