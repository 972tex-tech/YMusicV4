#include "quickjs_mse.h"

#include <limits.h>
#include <string.h>
#ifdef __APPLE__
#include <TargetConditionals.h>
#endif

static const QjsMseVt *g_vt;

void qjs_mse_set_vt(const QjsMseVt *vt)
{
    g_vt = vt;
}

extern void *qjs_ctx_mse(JSContext *ctx);

static void *host_of(JSContext *ctx)
{
    return qjs_ctx_mse(ctx);
}

static JSValue throw_no_mse(JSContext *ctx)
{
    return JS_ThrowTypeError(ctx, "native MSE host not attached");
}

static JSValue js_ready(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    (void)this_val;
    (void)argc;
    (void)argv;
    return JS_NewBool(ctx, g_vt && host_of(ctx));
}

static JSValue js_current_time(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    return JS_NewFloat64(ctx, g_vt->current_time(h));
}

static JSValue js_set_current_time(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    double t = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 1 || JS_ToFloat64(ctx, &t, argv[0]))
        return JS_EXCEPTION;
    g_vt->set_current_time(h, t);
    return JS_UNDEFINED;
}

static JSValue js_duration(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    return JS_NewFloat64(ctx, g_vt->duration(h));
}

static JSValue js_paused(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    return JS_NewBool(ctx, g_vt->paused(h));
}

static JSValue js_ready_state(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    return JS_NewInt32(ctx, g_vt->ready_state(h));
}

static JSValue js_play(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    g_vt->play(h);
    return JS_UNDEFINED;
}

static JSValue js_pause(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    g_vt->pause(h);
    return JS_UNDEFINED;
}

static JSValue js_append(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int audio = 0;
    double ts = 0, ws = 0, we = 1e300;
    size_t size = 0, bpe = 0;
    size_t offset = 0;
    JSValue buf;
    uint8_t *p;
    size_t len = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 2)
        return JS_ThrowTypeError(ctx, "__mseAppend(audio, bytes, ts, winStart, winEnd)");
    if (JS_ToInt32(ctx, &audio, argv[0]))
        return JS_EXCEPTION;
    if (argc > 2 && JS_ToFloat64(ctx, &ts, argv[2]))
        return JS_EXCEPTION;
    if (argc > 3 && JS_ToFloat64(ctx, &ws, argv[3]))
        return JS_EXCEPTION;
    if (argc > 4 && JS_ToFloat64(ctx, &we, argv[4]))
        return JS_EXCEPTION;
    buf = JS_GetTypedArrayBuffer(ctx, argv[1], &offset, &size, &bpe);
    if (JS_IsException(buf))
        return buf;
    p = JS_GetArrayBuffer(ctx, &len, buf);
    JS_FreeValue(ctx, buf);
    if (!p)
        return JS_ThrowTypeError(ctx, "__mseAppend: not a Uint8Array");
    if (offset + size > len)
        return JS_ThrowRangeError(ctx, "__mseAppend: OOB");
    if (size > (size_t)INT_MAX)
        return JS_ThrowRangeError(ctx, "__mseAppend: too large");
    if (!g_vt->append(h, audio, p + offset, (int)size, ts, ws, we))
        return JS_ThrowInternalError(ctx, "__mseAppend failed");
    return JS_UNDEFINED;
}

static JSValue js_remove(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int audio = 0;
    double start = 0, end = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 3)
        return JS_ThrowTypeError(ctx, "__mseRemove(audio, start, end)");
    if (JS_ToInt32(ctx, &audio, argv[0]) || JS_ToFloat64(ctx, &start, argv[1]) || JS_ToFloat64(ctx, &end, argv[2]))
        return JS_EXCEPTION;
    g_vt->remove(h, audio, start, end);
    return JS_UNDEFINED;
}

static JSValue js_abort(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int audio = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 1 || JS_ToInt32(ctx, &audio, argv[0]))
        return JS_EXCEPTION;
    g_vt->abort(h, audio);
    return JS_UNDEFINED;
}

static JSValue js_change_type(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int audio = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 1 || JS_ToInt32(ctx, &audio, argv[0]))
        return JS_EXCEPTION;
    if (g_vt->change_type)
        g_vt->change_type(h, audio);
    return JS_UNDEFINED;
}

static JSValue js_rest_video(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int on = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 1 || JS_ToInt32(ctx, &on, argv[0]))
        return JS_EXCEPTION;
    if (g_vt->rest_video)
        g_vt->rest_video(h, on);
    return JS_UNDEFINED;
}

static JSValue js_set_duration(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    double t = 0;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 1 || JS_ToFloat64(ctx, &t, argv[0]))
        return JS_EXCEPTION;
    if (g_vt->set_duration)
        g_vt->set_duration(h, t);
    return JS_UNDEFINED;
}

static JSValue js_buffered(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    int audio = 0, n = 0, i;
    double pairs[64];
    JSValue arr;
    (void)this_val;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    if (argc < 1 || JS_ToInt32(ctx, &audio, argv[0]))
        return JS_EXCEPTION;
    n = g_vt->buffered(h, audio, pairs, 32);
    arr = JS_NewArray(ctx);
    for (i = 0; i < n; i++) {
        JS_SetPropertyUint32(ctx, arr, (unsigned)(i * 2), JS_NewFloat64(ctx, pairs[i * 2]));
        JS_SetPropertyUint32(ctx, arr, (unsigned)(i * 2 + 1), JS_NewFloat64(ctx, pairs[i * 2 + 1]));
    }
    return arr;
}

static JSValue js_reset(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    void *h = host_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!g_vt || !h)
        return throw_no_mse(ctx);
    g_vt->reset(h);
    return JS_UNDEFINED;
}

/* ffmpeg's `av1` decoder is VideoToolbox-only. iOS Simulator has no AV1 HW
   and this tree does not link libdav1d, so claiming av01 makes YouTube pick
   a format we cannot send() (ENOSYS, fps=0). Device VT AV1 stays on. */
static JSValue js_av1_ok(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    (void)this_val;
    (void)argc;
    (void)argv;
#if defined(__APPLE__) && TARGET_OS_SIMULATOR
    return JS_NewInt32(ctx, 0);
#else
    return JS_NewInt32(ctx, 1);
#endif
}

/* iOS libav has no libvpx. Native ffmpeg `vp9` SIGSEGV in receive_frame on
   live DVR seek (MseLab 14:03, FAR 0x28). VT-VP9 is not available on most
   iPhones or the simulator — live uses H.264 VT instead. */
static JSValue js_vp9_ok(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    (void)this_val;
    (void)argc;
    (void)argv;
#if TARGET_OS_IPHONE
    return JS_NewInt32(ctx, 0);
#else
    return JS_NewInt32(ctx, 1);
#endif
}

void qjs_mse_install(JSContext *ctx)
{
    JSValue g = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, g, "__mseReady", JS_NewCFunction(ctx, js_ready, "__mseReady", 0));
    JS_SetPropertyStr(ctx, g, "__mseCurrentTime", JS_NewCFunction(ctx, js_current_time, "__mseCurrentTime", 0));
    JS_SetPropertyStr(ctx, g, "__mseSetCurrentTime", JS_NewCFunction(ctx, js_set_current_time, "__mseSetCurrentTime", 1));
    JS_SetPropertyStr(ctx, g, "__mseDuration", JS_NewCFunction(ctx, js_duration, "__mseDuration", 0));
    JS_SetPropertyStr(ctx, g, "__mseSetDuration", JS_NewCFunction(ctx, js_set_duration, "__mseSetDuration", 1));
    JS_SetPropertyStr(ctx, g, "__msePaused", JS_NewCFunction(ctx, js_paused, "__msePaused", 0));
    JS_SetPropertyStr(ctx, g, "__mseReadyState", JS_NewCFunction(ctx, js_ready_state, "__mseReadyState", 0));
    JS_SetPropertyStr(ctx, g, "__msePlay", JS_NewCFunction(ctx, js_play, "__msePlay", 0));
    JS_SetPropertyStr(ctx, g, "__msePause", JS_NewCFunction(ctx, js_pause, "__msePause", 0));
    JS_SetPropertyStr(ctx, g, "__mseAppend", JS_NewCFunction(ctx, js_append, "__mseAppend", 5));
    JS_SetPropertyStr(ctx, g, "__mseRemove", JS_NewCFunction(ctx, js_remove, "__mseRemove", 3));
    JS_SetPropertyStr(ctx, g, "__mseAbort", JS_NewCFunction(ctx, js_abort, "__mseAbort", 1));
    JS_SetPropertyStr(ctx, g, "__mseChangeType", JS_NewCFunction(ctx, js_change_type, "__mseChangeType", 1));
    JS_SetPropertyStr(ctx, g, "__mseRestVideo", JS_NewCFunction(ctx, js_rest_video, "__mseRestVideo", 1));
    JS_SetPropertyStr(ctx, g, "__mseBuffered", JS_NewCFunction(ctx, js_buffered, "__mseBuffered", 1));
    JS_SetPropertyStr(ctx, g, "__mseReset", JS_NewCFunction(ctx, js_reset, "__mseReset", 0));
    JS_SetPropertyStr(ctx, g, "__mseAv1Ok", JS_NewCFunction(ctx, js_av1_ok, "__mseAv1Ok", 0));
    JS_SetPropertyStr(ctx, g, "__mseVp9Ok", JS_NewCFunction(ctx, js_vp9_ok, "__mseVp9Ok", 0));
    JS_FreeValue(ctx, g);
}
