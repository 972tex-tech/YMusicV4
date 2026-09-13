#ifdef __APPLE__
#import "mse_vt_present.h"
#import "mse_ffmpeg.h"
#import <jni.h>
#import <jawt.h>
#import <jawt_md.h>
#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>
#import <QuartzCore/QuartzCore.h>
#import <dlfcn.h>
#import <string.h>

void *mse_vt_jawt_layer;

static jboolean (*mse_jawt_get_awt)(JNIEnv *, JAWT *);

static int jawt_bind(void)
{
    void *h;
    if (mse_jawt_get_awt)
        return 1;
    /* System.loadLibrary("jawt") already mapped this; dlopen finds it. */
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

void *mse_vt_attach_component(void *envp, void *component)
{
    JNIEnv *env = (JNIEnv *)envp;
    jobject obj = (jobject)component;
    jclass canvas;
    JAWT awt;
    JAWT_DrawingSurface *ds;
    JAWT_DrawingSurfaceInfo *dsi;
    id<JAWT_SurfaceLayers> sl;
    AVSampleBufferDisplayLayer *layer;
    jint lock;
    void *retained;

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
    layer = [AVSampleBufferDisplayLayer layer];
    layer.videoGravity = AVLayerVideoGravityResizeAspect;
    layer.autoresizingMask = kCALayerWidthSizable | kCALayerHeightSizable;
    layer.actions = @{ @"contents": [NSNull null], @"bounds": [NSNull null] };
    sl.layer = layer;
    retained = (void *)CFBridgingRetain(layer);
    ds->FreeDrawingSurfaceInfo(dsi);
    ds->Unlock(ds);
    awt.FreeDrawingSurface(ds);
    if (mse_vt_jawt_layer && mse_vt_jawt_layer != retained)
        mse_vt_detach(mse_vt_jawt_layer);
    mse_vt_jawt_layer = retained;
    mse_logf("mse-v jawt layer attached\n");
    return retained;
}
#endif
