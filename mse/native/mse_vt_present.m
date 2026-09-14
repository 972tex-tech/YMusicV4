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
#import <math.h>
#import <string.h>

void *mse_vt_jawt_layer;
static NSView *mse_vt_jawt_view;
static id mse_end_live_obs;
static NSWindow *mse_live_win;
static int mse_pending_layout;
static int mse_px, mse_py, mse_pw, mse_ph;

/* Overlay sits on AWTView and takes the hit. Forward mouse-up to Java
   (FileAvNative.onOverlayMouse) so the desktop shell can toggle chrome.
   Do not copy events into Compose — that froze AppKit. */
static JavaVM *mse_jvm;
static jclass mse_native_cls;
static jmethodID mse_mouse_mid;
static jmethodID mse_live_mid;
static int mse_live_notified;
static int mse_overlay_deferred_show;

static void mse_bind_mouse_jni(JNIEnv *env)
{
    jclass local;
    if (mse_jvm && mse_native_cls && mse_mouse_mid && mse_live_mid)
        return;
    if (!env)
        return;
    (*env)->GetJavaVM(env, &mse_jvm);
    local = (*env)->FindClass(env, "io/ymusic/mse/FileAvNative");
    if (!local) {
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
        mse_logf("mse-v mouse FindClass FileAvNative failed\n");
        return;
    }
    mse_native_cls = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    mse_mouse_mid = (*env)->GetStaticMethodID(env, mse_native_cls, "onOverlayMouse", "(IIIIIII)V");
    if (!mse_mouse_mid) {
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
        mse_logf("mse-v mouse onOverlayMouse mid failed\n");
    }
    mse_live_mid = (*env)->GetStaticMethodID(env, mse_native_cls, "onOverlayLiveResize", "(Z)V");
    if (!mse_live_mid && (*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
}

static void mse_java_live_resize(int on)
{
    JNIEnv *env = NULL;
    if (!mse_jvm || !mse_native_cls || !mse_live_mid)
        return;
    if ((*mse_jvm)->GetEnv(mse_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
        (*mse_jvm)->AttachCurrentThread(mse_jvm, (void **)&env, NULL);
    if (!env)
        return;
    (*env)->CallStaticVoidMethod(env, mse_native_cls, mse_live_mid, on ? JNI_TRUE : JNI_FALSE);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
}

static jint mse_awt_mods(NSEvent *e)
{
    jint m = 0;
    NSUInteger b = NSEvent.pressedMouseButtons;
    if (e.modifierFlags & NSEventModifierFlagShift)
        m |= 64;
    if (e.modifierFlags & NSEventModifierFlagControl)
        m |= 128;
    if (e.modifierFlags & NSEventModifierFlagCommand)
        m |= 256;
    if (e.modifierFlags & NSEventModifierFlagOption)
        m |= 512;
    if (b & 1)
        m |= 1024;
    if (b & 4)
        m |= 2048;
    if (b & 2)
        m |= 4096;
    return m;
}

static jint mse_awt_id(NSEventType t)
{
    switch (t) {
    case NSEventTypeLeftMouseDown:
    case NSEventTypeRightMouseDown:
    case NSEventTypeOtherMouseDown:
        return 501;
    case NSEventTypeLeftMouseUp:
    case NSEventTypeRightMouseUp:
    case NSEventTypeOtherMouseUp:
        return 502;
    case NSEventTypeMouseMoved:
        return 503;
    case NSEventTypeMouseEntered:
        return 504;
    case NSEventTypeMouseExited:
        return 505;
    case NSEventTypeLeftMouseDragged:
    case NSEventTypeRightMouseDragged:
    case NSEventTypeOtherMouseDragged:
        return 506;
    case NSEventTypeScrollWheel:
        return 507;
    default:
        return 503;
    }
}

static jint mse_awt_button(NSEventType t)
{
    switch (t) {
    case NSEventTypeLeftMouseDown:
    case NSEventTypeLeftMouseUp:
    case NSEventTypeLeftMouseDragged:
        return 1;
    case NSEventTypeOtherMouseDown:
    case NSEventTypeOtherMouseUp:
    case NSEventTypeOtherMouseDragged:
        return 2;
    case NSEventTypeRightMouseDown:
    case NSEventTypeRightMouseUp:
    case NSEventTypeRightMouseDragged:
        return 3;
    default:
        return 0;
    }
}

static void mse_java_mouse(NSEvent *e)
{
    NSPoint scr;
    CGFloat top;
    jint x, y, wheel, clicks, eid, mods, button;

    if (!e || !e.window || !mse_jvm || !mse_native_cls || !mse_mouse_mid)
        return;
    scr = [e.window convertPointToScreen:e.locationInWindow];
    top = e.window.screen ? NSMaxY(e.window.screen.frame) : NSMaxY(NSScreen.mainScreen.frame);
    x = (jint)llround(scr.x);
    y = (jint)llround(top - scr.y);
    wheel = (e.type == NSEventTypeScrollWheel) ? (jint)llround(-e.scrollingDeltaY) : 0;
    switch (e.type) {
    case NSEventTypeLeftMouseDown:
    case NSEventTypeLeftMouseUp:
    case NSEventTypeRightMouseDown:
    case NSEventTypeRightMouseUp:
    case NSEventTypeOtherMouseDown:
    case NSEventTypeOtherMouseUp:
        clicks = (jint)e.clickCount;
        break;
    default:
        clicks = 0;
        break;
    }
    eid = mse_awt_id(e.type);
    mods = mse_awt_mods(e);
    button = mse_awt_button(e.type);
    /* Return from NSView mouseDown before Java. AWT EDT is AppKit main:
       dispatchEvent/toFront from this stack freezes the UI. */
    dispatch_async(dispatch_get_main_queue(), ^{
        JNIEnv *async_env = NULL;
        if ((*mse_jvm)->GetEnv(mse_jvm, (void **)&async_env, JNI_VERSION_1_6) != JNI_OK)
            (*mse_jvm)->AttachCurrentThread(mse_jvm, (void **)&async_env, NULL);
        if (!async_env || !mse_native_cls || !mse_mouse_mid)
            return;
        (*async_env)->CallStaticVoidMethod(async_env, mse_native_cls, mse_mouse_mid,
                                           eid, x, y, mods, button, clicks, wheel);
        if ((*async_env)->ExceptionCheck(async_env))
            (*async_env)->ExceptionClear(async_env);
    });
}

@interface MseVtHostView : NSView
@end
@implementation MseVtHostView
- (NSView *)hitTest:(NSPoint)point
{
    /* Under AWTView we should not be hit. If z-order is wrong, still take the
       click so JNI can toggle chrome rather than dropping it. */
    return NSPointInRect(point, self.frame) ? self : nil;
}
#define MSE_JNI_MOUSE(sel) - (void)sel:(NSEvent *)e { mse_java_mouse(e); }
MSE_JNI_MOUSE(mouseDown)
MSE_JNI_MOUSE(mouseUp)
MSE_JNI_MOUSE(mouseDragged)
MSE_JNI_MOUSE(rightMouseDown)
MSE_JNI_MOUSE(rightMouseUp)
MSE_JNI_MOUSE(otherMouseDown)
MSE_JNI_MOUSE(otherMouseUp)
MSE_JNI_MOUSE(scrollWheel)
#undef MSE_JNI_MOUSE
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

/* Always async. AWT's EDT is AppKit main: inlining during Canvas removeNotify /
   addNotify (mini player → Now Playing) re-enters AppKit while JAWT still owns
   the surface and freezes the UI. Never dispatch_sync. */
static void mse_on_main(void (^block)(void))
{
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

void mse_vt_set_overlay_hidden(int hid)
{
    NSView *v = mse_vt_jawt_view;
    if (hid) {
        mse_overlay_deferred_show = 0;
        if (!v)
            return;
        if ([NSThread isMainThread])
            v.hidden = YES;
        else
            mse_on_main(^{
                v.hidden = YES;
            });
        return;
    }
    /* Stay hidden until mse_vt_reveal_overlay (next presented frame). */
    mse_overlay_deferred_show = 1;
}

void mse_vt_reveal_overlay(void)
{
    NSView *v;
    if (!mse_overlay_deferred_show)
        return;
    mse_overlay_deferred_show = 0;
    v = mse_vt_jawt_view;
    if (!v)
        return;
    if ([NSThread isMainThread])
        v.hidden = NO;
    else
        mse_on_main(^{
            NSView *host = mse_vt_jawt_view;
            if (host)
                host.hidden = NO;
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
            if (!mse_live_notified) {
                mse_live_notified = 1;
                mse_java_live_resize(1);
            }
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
                                if (mse_pending_layout) {
                                    mse_pending_layout = 0;
                                    mse_apply_host_frame(mse_px, mse_py, mse_pw, mse_ph);
                                }
                                if (mse_live_notified) {
                                    mse_live_notified = 0;
                                    mse_java_live_resize(0);
                                }
                            }];
            }
            return;
        }
        mse_pending_layout = 0;
        mse_apply_host_frame(x, y, w, h);
    });
}

static void *mse_vt_host_new(CALayer *wl, CALayer *canvas_layer, jint x, jint y, jint w, jint h)
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
        /* Child of AWTView: the picture is visible. A sibling under AWTView is a
           black hole (Metal does not composite through, and NSThemeFrame rejects
           unknown subviews). Compose chrome over the hole is copied via JNI. */
        frame = mse_screen_to_view(parent, x, y, w, h);
        host = [[MseVtHostView alloc] initWithFrame:frame];
        host.wantsLayer = YES;
        host.layer = (__bridge AVSampleBufferDisplayLayer *)retained;
        [parent addSubview:host];
        host.window.acceptsMouseMovedEvents = YES;
        mse_vt_jawt_view = host;
        (void)canvas_layer;
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
    mse_bind_mouse_jni(env);
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
    {
        CALayer *cl = sl.layer;
        ds->FreeDrawingSurfaceInfo(dsi);
        ds->Unlock(ds);
        awt.FreeDrawingSurface(ds);
        retained = mse_vt_host_new(wl, cl, jx, jy, jw, jh);
        return retained;
    }
    return retained;
}
#endif
