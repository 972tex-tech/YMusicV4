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

void mse_vt_jawt_unhost(void)
{
    NSView *v = mse_vt_jawt_view;
    mse_vt_jawt_view = nil;
    if (!v)
        return;
    void (^rm)(void) = ^{
        [v removeFromSuperview];
    };
    if ([NSThread isMainThread])
        rm();
    else
        dispatch_sync(dispatch_get_main_queue(), rm);
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
    /* Theme frame rejects unknown subviews and sits under the title bar. */
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

/* AWT locationOnScreen is top-left, y-down. Map into [parent]. */
static NSRect mse_screen_to_view(NSView *parent, jint x, jint y, jint w, jint h)
{
    NSRect screen, win;
    CGFloat top = NSMaxY(parent.window.screen.frame);

    screen = NSMakeRect((CGFloat)x, top - (CGFloat)y - (CGFloat)h, (CGFloat)w, (CGFloat)h);
    win = [parent.window convertRectFromScreen:screen];
    return [parent convertRect:win fromView:nil];
}

static void *mse_vt_attach_main(id<JAWT_SurfaceLayers> sl, int have_java,
                                jint jx, jint jy, jint jw, jint jh)
{
    NSView *parent;
    NSRect frame;
    NSView *host;
    AVSampleBufferDisplayLayer *layer;
    void *retained;

    if (!have_java) {
        mse_logf("mse-v jawt no on-screen bounds, wait for layout\n");
        return NULL;
    }
    parent = mse_content_view(sl.windowLayer);
    if (!parent) {
        mse_logf("mse-v jawt no content view\n");
        return NULL;
    }
    frame = mse_screen_to_view(parent, jx, jy, jw, jh);
    if (frame.size.width < 1 || frame.size.height < 1) {
        mse_logf("mse-v jawt view frame empty\n");
        return NULL;
    }
    if (mse_vt_jawt_view && mse_vt_jawt_layer) {
        mse_vt_jawt_view.frame = frame;
        mse_logf("mse-v jawt layer layout %.0fx%.0f @ %.0f,%.0f\n",
                 frame.size.width, frame.size.height, frame.origin.x, frame.origin.y);
        return mse_vt_jawt_layer;
    }
    layer = [AVSampleBufferDisplayLayer layer];
    layer.videoGravity = AVLayerVideoGravityResizeAspect;
    layer.actions = @{ @"contents": [NSNull null], @"bounds": [NSNull null], @"frame": [NSNull null] };
    host = [[NSView alloc] initWithFrame:frame];
    host.wantsLayer = YES;
    host.layer = layer;
    [parent addSubview:host];
    retained = (void *)CFBridgingRetain(layer);
    if (mse_vt_jawt_layer && mse_vt_jawt_layer != retained)
        mse_vt_detach(mse_vt_jawt_layer);
    mse_vt_jawt_layer = retained;
    mse_vt_jawt_view = host;
    mse_logf("mse-v jawt layer attached %.0fx%.0f @ %.0f,%.0f parent=%s flipped=%d\n",
             frame.size.width, frame.size.height, frame.origin.x, frame.origin.y,
             parent.className.UTF8String ?: "?", parent.flipped ? 1 : 0);
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
    jint lock, jx = 0, jy = 0, jw = 0, jh = 0, have_java = 0;
    __block void *retained = NULL;

    if (!env || !obj)
        return NULL;
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
    lock = ds->Lock(ds);
    if ((lock & JAWT_LOCK_ERROR) != 0) {
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
    have_java = mse_java_screen_rect(env, obj, &jx, &jy, &jw, &jh) == 0;
    {
        void (^work)(void) = ^{
            retained = mse_vt_attach_main(sl, have_java, jx, jy, jw, jh);
        };
        if ([NSThread isMainThread])
            work();
        else
            dispatch_sync(dispatch_get_main_queue(), work);
    }
    ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    return retained;
}
#endif
