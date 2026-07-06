/*
 * Minimal JNI transport for QuickJS (jvm/android). The pipe multiplexer speaks a few operations per
 * direction instead of one framed byte blob:
 *   host -> JS : open(id,cmd) / write(id,bytes,off,len)->accepted / closeWrite(id,reason) /
 *                closeRead(id) / notify(id,code)
 *                -> JS globals  __jsOpen / __jsData / __jsClose / __jsCloseRead / __jsNotify
 *   JS -> host : JS globals  __hostOpen / __hostData / __hostClose / __hostCloseRead / __hostNotify
 *                -> statics  QuickJsNative.onOpen/onWrite/onCloseWrite/onCloseRead/onNotify
 * Each pipe direction is a bounded (64 KB), flow-controlled byte stream. Payloads cross ZERO-COPY:
 *   host -> JS : the ByteArray is held with GetPrimitiveArrayCritical and exposed to __jsData as a
 *                one-shot ArrayBuffer view (detached before return — see quickjs_pipe.h);
 *   JS -> host : the Uint8Array's bytes are exposed to onWrite as a direct ByteBuffer (valid only
 *                during the call).
 * write()'s return is the accepted byte count (backpressure: wait for a DRAIN notify); -1 = EPIPE.
 * closeWrite(reason=null) = END; non-null = RESET. ALL routing lives in Kotlin and JS.
 *
 * CRITICAL-REGION DISCIPLINE: while the host ByteArray is held critically, __jsData executes JS —
 * that synchronous path must never re-enter JNI. The bootstrap honors this (its _data path only
 * calls pure-C natives like __segTake; promise resolutions merely SCHEDULE jobs), the microtask
 * pump runs only after the critical release, and this file enforces it: under in_critical,
 * __hostNotify is queued (flushed after release) and any other __host* call throws.
 *
 * Strings that cross (commands, reasons, eval source/result, errors) use standard UTF-8 <-> UTF-16
 * (NewString/GetStringChars), never NewStringUTF, so astral characters survive. Single-thread
 * confinement: every JNI entry point runs on the one Kotlin engine thread.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "quickjs.h"
#include "quickjs_json.h"
#include "quickjs_pipe.h"

#define PENDING_NOTIFY_MAX 16

typedef struct Engine {
    JSRuntime *rt;
    JSContext *ctx;
    JavaVM *jvm;
    QjsPool *pool;
    jclass bridgeClass;   /* global ref to ymusic.quickjs.QuickJsNative */
    jmethodID onOpen;     /* static void onOpen(long, int, String) */
    jmethodID onWrite;    /* static int  onWrite(long, int, ByteBuffer) */
    jmethodID onCloseWrite; /* static void onCloseWrite(long, int, String|null) */
    jmethodID onCloseRead;  /* static void onCloseRead(long, int) */
    jmethodID onNotify;   /* static void onNotify(long, int, int) */
    JSValue jsOpen, jsData, jsClose, jsCloseRead, jsNotify; /* JS mux entry points (C -> JS) */
    int in_critical;      /* a host ByteArray is held critically: JNI re-entry is forbidden */
    struct { uint32_t id; int code; } pending[PENDING_NOTIFY_MAX];
    int pending_count;
} Engine;

/* ---- UTF-8 (standard) <-> UTF-16 (astral-correct) ---- */

static jstring utf8_to_jstring(JNIEnv *env, const char *s, size_t len) {
    jsize u16len = 0;
    size_t i = 0;
    while (i < len) {
        unsigned char c = (unsigned char)s[i];
        int adv = (c < 0x80) ? 1 : ((c & 0xE0) == 0xC0) ? 2 : ((c & 0xF0) == 0xE0) ? 3
                 : ((c & 0xF8) == 0xF0) ? 4 : 1;
        if (i + (size_t)adv > len) adv = 1;
        u16len += (adv == 4) ? 2 : 1;
        i += adv;
    }
    jchar *buf = (jchar *)malloc(sizeof(jchar) * (u16len > 0 ? u16len : 1));
    jsize k = 0;
    i = 0;
    while (i < len) {
        unsigned char c = (unsigned char)s[i];
        uint32_t cp;
        int adv;
        if (c < 0x80) { cp = c; adv = 1; }
        else if ((c & 0xE0) == 0xC0 && i + 2 <= len) { cp = ((uint32_t)(c & 0x1F) << 6) | (s[i+1] & 0x3F); adv = 2; }
        else if ((c & 0xF0) == 0xE0 && i + 3 <= len) { cp = ((uint32_t)(c & 0x0F) << 12) | ((uint32_t)(s[i+1] & 0x3F) << 6) | (s[i+2] & 0x3F); adv = 3; }
        else if ((c & 0xF8) == 0xF0 && i + 4 <= len) { cp = ((uint32_t)(c & 0x07) << 18) | ((uint32_t)(s[i+1] & 0x3F) << 12) | ((uint32_t)(s[i+2] & 0x3F) << 6) | (s[i+3] & 0x3F); adv = 4; }
        else { cp = 0xFFFD; adv = 1; }
        if (cp > 0xFFFF) {
            cp -= 0x10000;
            buf[k++] = (jchar)(0xD800 + (cp >> 10));
            buf[k++] = (jchar)(0xDC00 + (cp & 0x3FF));
        } else {
            buf[k++] = (jchar)cp;
        }
        i += adv;
    }
    jstring result = (*env)->NewString(env, buf, u16len);
    free(buf);
    return result;
}

static char *jstring_to_utf8(JNIEnv *env, jstring s, size_t *outLen) {
    jsize n = (*env)->GetStringLength(env, s);
    const jchar *u = (*env)->GetStringChars(env, s, NULL);
    char *buf = (char *)malloc((size_t)n * 4 + 1);
    size_t k = 0;
    for (jsize i = 0; i < n; i++) {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n) {
            jchar lo = u[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) { cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00); i++; }
        }
        if (cp < 0x80) buf[k++] = (char)cp;
        else if (cp < 0x800) { buf[k++] = (char)(0xC0 | (cp >> 6)); buf[k++] = (char)(0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) { buf[k++] = (char)(0xE0 | (cp >> 12)); buf[k++] = (char)(0x80 | ((cp >> 6) & 0x3F)); buf[k++] = (char)(0x80 | (cp & 0x3F)); }
        else { buf[k++] = (char)(0xF0 | (cp >> 18)); buf[k++] = (char)(0x80 | ((cp >> 12) & 0x3F)); buf[k++] = (char)(0x80 | ((cp >> 6) & 0x3F)); buf[k++] = (char)(0x80 | (cp & 0x3F)); }
    }
    buf[k] = 0;
    (*env)->ReleaseStringChars(env, s, u);
    if (outLen) *outLen = k;
    return buf;
}

static void throw_qjs(JNIEnv *env, jstring msg) {
    jclass ex = (*env)->FindClass(env, "ymusic/quickjs/QuickJsException");
    jmethodID ctor = (*env)->GetMethodID(env, ex, "<init>", "(Ljava/lang/String;)V");
    jobject o = (*env)->NewObject(env, ex, ctor, msg);
    (*env)->Throw(env, (jthrowable)o);
}

static void pump_jobs(Engine *e) {
    JSContext *c;
    while (JS_ExecutePendingJob(e->rt, &c) > 0) { /* run microtasks */ }
}

static JNIEnv *engine_env(Engine *e) {
    JNIEnv *env;
    (*e->jvm)->GetEnv(e->jvm, (void **)&env, JNI_VERSION_1_6);
    return env;
}

/* Notifies queued while a critical region was held (see file comment) — flush once JNI is legal. */
static void flush_pending(Engine *e, JNIEnv *env) {
    for (int i = 0; i < e->pending_count; i++)
        (*env)->CallStaticVoidMethod(env, e->bridgeClass, e->onNotify,
                                     (jlong)(intptr_t)e, (jint)e->pending[i].id, (jint)e->pending[i].code);
    e->pending_count = 0;
}

/* ---- inbound: JS -> host (JS calls these) ---- */

/* __hostOpen(id, command) */
static JSValue js_host_open(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    if (e->in_critical) return JS_ThrowInternalError(ctx, "__hostOpen during data delivery");
    JNIEnv *env = engine_env(e);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    size_t len = 0;
    const char *cmd = JS_ToCStringLen(ctx, &len, argv[1]);
    jstring jcmd = utf8_to_jstring(env, cmd ? cmd : "", cmd ? len : 0);
    if (cmd) JS_FreeCString(ctx, cmd);
    (*env)->CallStaticVoidMethod(env, e->bridgeClass, e->onOpen, (jlong)(intptr_t)e, (jint)id, jcmd);
    (*env)->DeleteLocalRef(env, jcmd);
    return JS_UNDEFINED;
}

/* __hostData(id, u8, off, len) -> accepted byte count (or -1 EPIPE). The JS bytes cross as a direct
 * ByteBuffer over the typed array's memory — valid only during the call; the Kotlin mux consumes it
 * synchronously (parked reader's ByteArray and/or its okio buffer). */
static JSValue js_host_data(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    if (e->in_critical) return JS_ThrowInternalError(ctx, "__hostData during data delivery");
    JNIEnv *env = engine_env(e);
    uint32_t id = 0;
    int64_t off = 0, len = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    JS_ToInt64(ctx, &off, argv[2]);
    JS_ToInt64(ctx, &len, argv[3]);
    jint rc = -1;
    size_t ta_off = 0, ta_len = 0, bpe = 0;
    JSValue ab = JS_GetTypedArrayBuffer(ctx, argv[1], &ta_off, &ta_len, &bpe);
    if (!JS_IsException(ab)) {
        size_t sz = 0;
        uint8_t *ptr = JS_GetArrayBuffer(ctx, &sz, ab);
        if (ptr && len > 0) {
            jobject bb = (*env)->NewDirectByteBuffer(env, ptr + ta_off + off, (jlong)len);
            rc = (*env)->CallStaticIntMethod(env, e->bridgeClass, e->onWrite, (jlong)(intptr_t)e, (jint)id, bb);
            (*env)->DeleteLocalRef(env, bb);
        } else {
            rc = 0;
        }
    }
    JS_FreeValue(ctx, ab);
    return JS_NewInt32(ctx, rc);
}

/* __hostClose(id, reason)  — write-side half-close: reason null/undefined = END, string = RESET */
static JSValue js_host_close(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    if (e->in_critical) return JS_ThrowInternalError(ctx, "__hostClose during data delivery");
    JNIEnv *env = engine_env(e);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    jstring jreason = NULL;
    if (argc > 1 && !JS_IsNull(argv[1]) && !JS_IsUndefined(argv[1])) {
        size_t rlen = 0;
        const char *reason = JS_ToCStringLen(ctx, &rlen, argv[1]);
        jreason = utf8_to_jstring(env, reason ? reason : "", reason ? rlen : 0);
        if (reason) JS_FreeCString(ctx, reason);
    }
    (*env)->CallStaticVoidMethod(env, e->bridgeClass, e->onCloseWrite, (jlong)(intptr_t)e, (jint)id, jreason);
    if (jreason) (*env)->DeleteLocalRef(env, jreason);
    return JS_UNDEFINED;
}

/* __hostCloseRead(id) — abandon the read end: the host's next write gets EPIPE */
static JSValue js_host_close_read(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    if (e->in_critical) return JS_ThrowInternalError(ctx, "__hostCloseRead during data delivery");
    JNIEnv *env = engine_env(e);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    (*env)->CallStaticVoidMethod(env, e->bridgeClass, e->onCloseRead, (jlong)(intptr_t)e, (jint)id);
    return JS_UNDEFINED;
}

/* __hostNotify(id, code)  — flow control (DRAIN). Queued when fired under a critical region. */
static JSValue js_host_notify(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    uint32_t id = 0;
    int32_t code = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    JS_ToInt32(ctx, &code, argv[1]);
    if (e->in_critical) {
        if (e->pending_count < PENDING_NOTIFY_MAX) {
            e->pending[e->pending_count].id = id;
            e->pending[e->pending_count].code = code;
            e->pending_count++;
            return JS_UNDEFINED;
        }
        return JS_ThrowInternalError(ctx, "__hostNotify overflow during data delivery");
    }
    JNIEnv *env = engine_env(e);
    (*env)->CallStaticVoidMethod(env, e->bridgeClass, e->onNotify, (jlong)(intptr_t)e, (jint)id, (jint)code);
    return JS_UNDEFINED;
}

/* ---- JNI entry points (all on the single engine thread) ---- */

JNIEXPORT jlong JNICALL
Java_ymusic_quickjs_QuickJsNative_create(JNIEnv *env, jobject thiz, jstring jbootstrap) {
    Engine *e = (Engine *)calloc(1, sizeof(Engine));
    e->rt = JS_NewRuntime();
    e->ctx = JS_NewContext(e->rt);
    e->jsOpen = e->jsData = e->jsClose = e->jsCloseRead = e->jsNotify = JS_UNDEFINED;
    JS_SetContextOpaque(e->ctx, e);
    (*env)->GetJavaVM(env, &e->jvm);

    jclass local = (*env)->FindClass(env, "ymusic/quickjs/QuickJsNative");
    e->bridgeClass = (jclass)(*env)->NewGlobalRef(env, local);
    e->onOpen = (*env)->GetStaticMethodID(env, e->bridgeClass, "onOpen", "(JILjava/lang/String;)V");
    e->onWrite = (*env)->GetStaticMethodID(env, e->bridgeClass, "onWrite", "(JILjava/nio/ByteBuffer;)I");
    e->onCloseWrite = (*env)->GetStaticMethodID(env, e->bridgeClass, "onCloseWrite", "(JILjava/lang/String;)V");
    e->onCloseRead = (*env)->GetStaticMethodID(env, e->bridgeClass, "onCloseRead", "(JI)V");
    e->onNotify = (*env)->GetStaticMethodID(env, e->bridgeClass, "onNotify", "(JII)V");

    JSValue global = JS_GetGlobalObject(e->ctx);
    JS_SetPropertyStr(e->ctx, global, "__hostOpen", JS_NewCFunction(e->ctx, js_host_open, "__hostOpen", 2));
    JS_SetPropertyStr(e->ctx, global, "__hostData", JS_NewCFunction(e->ctx, js_host_data, "__hostData", 4));
    JS_SetPropertyStr(e->ctx, global, "__hostClose", JS_NewCFunction(e->ctx, js_host_close, "__hostClose", 2));
    JS_SetPropertyStr(e->ctx, global, "__hostCloseRead", JS_NewCFunction(e->ctx, js_host_close_read, "__hostCloseRead", 1));
    JS_SetPropertyStr(e->ctx, global, "__hostNotify", JS_NewCFunction(e->ctx, js_host_notify, "__hostNotify", 2));
    JS_FreeValue(e->ctx, global);
    qjs_register_json(e->ctx);
    e->pool = qjs_register_pipe(e->ctx);

    size_t blen = 0;
    char *bootstrap = jstring_to_utf8(env, jbootstrap, &blen);
    JSValue bret = JS_Eval(e->ctx, bootstrap, blen, "<bootstrap>", JS_EVAL_TYPE_GLOBAL);
    free(bootstrap);
    if (JS_IsException(bret)) {
        JSValue exc = JS_GetException(e->ctx);
        const char *msg = JS_ToCString(e->ctx, exc);
        throw_qjs(env, utf8_to_jstring(env, msg ? msg : "bootstrap failed", msg ? strlen(msg) : 16));
        if (msg) JS_FreeCString(e->ctx, msg);
        JS_FreeValue(e->ctx, exc);
        JS_FreeValue(e->ctx, bret);
        return 0;
    }
    JS_FreeValue(e->ctx, bret);

    JSValue g = JS_GetGlobalObject(e->ctx);
    e->jsOpen = JS_GetPropertyStr(e->ctx, g, "__jsOpen");
    e->jsData = JS_GetPropertyStr(e->ctx, g, "__jsData");
    e->jsClose = JS_GetPropertyStr(e->ctx, g, "__jsClose");
    e->jsCloseRead = JS_GetPropertyStr(e->ctx, g, "__jsCloseRead");
    e->jsNotify = JS_GetPropertyStr(e->ctx, g, "__jsNotify");
    JS_FreeValue(e->ctx, g);
    return (jlong)(intptr_t)e;
}

JNIEXPORT jstring JNICALL
Java_ymusic_quickjs_QuickJsNative_evalSync(JNIEnv *env, jobject thiz, jlong handle,
                                           jstring jscript, jstring jfilename) {
    Engine *e = (Engine *)(intptr_t)handle;
    size_t slen = 0;
    char *script = jstring_to_utf8(env, jscript, &slen);
    char *filename = jstring_to_utf8(env, jfilename, NULL);
    JSValue val = JS_Eval(e->ctx, script, slen, filename, JS_EVAL_TYPE_GLOBAL);
    free(script);
    free(filename);
    pump_jobs(e);
    if (JS_IsException(val)) {
        JSValue exc = JS_GetException(e->ctx);
        size_t mlen = 0;
        const char *msg = JS_ToCStringLen(e->ctx, &mlen, exc);
        jstring jmsg = utf8_to_jstring(env, msg ? msg : "error", msg ? mlen : 5);
        if (msg) JS_FreeCString(e->ctx, msg);
        JS_FreeValue(e->ctx, exc);
        JS_FreeValue(e->ctx, val);
        throw_qjs(env, jmsg);
        return NULL;
    }
    jstring result = NULL;
    if (!JS_IsUndefined(val) && !JS_IsNull(val)) {
        size_t rlen = 0;
        const char *res = JS_ToCStringLen(e->ctx, &rlen, val);
        if (res) { result = utf8_to_jstring(env, res, rlen); JS_FreeCString(e->ctx, res); }
    }
    JS_FreeValue(e->ctx, val);
    return result;
}

/* Host -> JS: call one of the JS mux entry points; return its int result (or exc_rc on a JS
 * exception). Frees argv, then runs the microtask pump. */
static int call_js(Engine *e, JSValue fn, int argc, JSValue *argv, int exc_rc) {
    JSValue g = JS_GetGlobalObject(e->ctx);
    JSValue r = JS_Call(e->ctx, fn, g, argc, argv);
    int rc = exc_rc;
    if (!JS_IsException(r)) JS_ToInt32(e->ctx, &rc, r);
    else JS_FreeValue(e->ctx, JS_GetException(e->ctx)); /* bootstrap bug; don't leave it pending */
    JS_FreeValue(e->ctx, r);
    JS_FreeValue(e->ctx, g);
    for (int i = 0; i < argc; i++) JS_FreeValue(e->ctx, argv[i]);
    pump_jobs(e);
    return rc;
}

JNIEXPORT void JNICALL
Java_ymusic_quickjs_QuickJsNative_open(JNIEnv *env, jobject thiz, jlong handle, jint id, jstring jcommand) {
    Engine *e = (Engine *)(intptr_t)handle;
    size_t len = 0;
    char *cmd = jstring_to_utf8(env, jcommand, &len);
    JSValue argv[2] = { JS_NewUint32(e->ctx, (uint32_t)id), JS_NewStringLen(e->ctx, cmd, len) };
    free(cmd);
    call_js(e, e->jsOpen, 2, argv, 0);
}

/* write(id, bytes, off, len) -> accepted. The ByteArray is held with GetPrimitiveArrayCritical for
 * the duration of the (synchronous) __jsData call — see the file comment for the discipline. */
JNIEXPORT jint JNICALL
Java_ymusic_quickjs_QuickJsNative_write(JNIEnv *env, jobject thiz, jlong handle, jint id,
                                        jbyteArray bytes, jint off, jint len) {
    Engine *e = (Engine *)(intptr_t)handle;
    if (len <= 0) return 0;
    jbyte *base = (*env)->GetPrimitiveArrayCritical(env, bytes, NULL);
    if (!base) return -1;
    e->in_critical = 1;
    JSValue ab = JS_UNDEFINED;
    JSValue view = qjs_view_wrap(e->ctx, (const uint8_t *)base + off, (size_t)len, &ab);
    int rc = -1;
    if (!JS_IsException(view)) {
        JSValue argv[2] = { JS_NewUint32(e->ctx, (uint32_t)id), JS_DupValue(e->ctx, view) };
        JSValue g = JS_GetGlobalObject(e->ctx);
        JSValue r = JS_Call(e->ctx, e->jsData, g, 2, argv);
        /* An exception is a bootstrap bug; report EPIPE so the writer fails loudly, never hangs. */
        if (!JS_IsException(r)) JS_ToInt32(e->ctx, &rc, r);
        else JS_FreeValue(e->ctx, JS_GetException(e->ctx));
        JS_FreeValue(e->ctx, r);
        JS_FreeValue(e->ctx, g);
        JS_FreeValue(e->ctx, argv[0]);
        JS_FreeValue(e->ctx, argv[1]);
        /* The array's memory stops being valid at release: detach BEFORE microtasks can run. */
        qjs_view_detach(e->ctx, view, ab);
    } else {
        JS_FreeValue(e->ctx, JS_GetException(e->ctx));
    }
    e->in_critical = 0;
    (*env)->ReleasePrimitiveArrayCritical(env, bytes, base, JNI_ABORT);
    flush_pending(e, env); /* notifies queued while critical — JNI is legal again */
    pump_jobs(e);
    return (jint)rc;
}

JNIEXPORT void JNICALL
Java_ymusic_quickjs_QuickJsNative_closeWrite(JNIEnv *env, jobject thiz, jlong handle, jint id, jstring jreason) {
    Engine *e = (Engine *)(intptr_t)handle;
    JSValue reason = JS_NULL;
    if (jreason != NULL) {
        size_t len = 0;
        char *r = jstring_to_utf8(env, jreason, &len);
        reason = JS_NewStringLen(e->ctx, r, len);
        free(r);
    }
    JSValue argv[2] = { JS_NewUint32(e->ctx, (uint32_t)id), reason };
    call_js(e, e->jsClose, 2, argv, 0);
}

JNIEXPORT void JNICALL
Java_ymusic_quickjs_QuickJsNative_closeRead(JNIEnv *env, jobject thiz, jlong handle, jint id) {
    Engine *e = (Engine *)(intptr_t)handle;
    JSValue argv[1] = { JS_NewUint32(e->ctx, (uint32_t)id) };
    call_js(e, e->jsCloseRead, 1, argv, 0);
}

JNIEXPORT void JNICALL
Java_ymusic_quickjs_QuickJsNative_notify(JNIEnv *env, jobject thiz, jlong handle, jint id, jint code) {
    Engine *e = (Engine *)(intptr_t)handle;
    JSValue argv[2] = { JS_NewUint32(e->ctx, (uint32_t)id), JS_NewInt32(e->ctx, (int)code) };
    call_js(e, e->jsNotify, 2, argv, 0);
}

JNIEXPORT void JNICALL
Java_ymusic_quickjs_QuickJsNative_close(JNIEnv *env, jobject thiz, jlong handle) {
    Engine *e = (Engine *)(intptr_t)handle;
    if (!e) return;
    JS_FreeValue(e->ctx, e->jsOpen);
    JS_FreeValue(e->ctx, e->jsData);
    JS_FreeValue(e->ctx, e->jsClose);
    JS_FreeValue(e->ctx, e->jsCloseRead);
    JS_FreeValue(e->ctx, e->jsNotify);
    (*env)->DeleteGlobalRef(env, e->bridgeClass);
    JS_FreeContext(e->ctx);
    JS_FreeRuntime(e->rt); /* segment free_funcs run here -> pool must still be alive */
    qjs_pool_free(e->pool);
    free(e);
}
