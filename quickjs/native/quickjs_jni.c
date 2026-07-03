/*
 * Minimal JNI transport for QuickJS (jvm/android). The pipe multiplexer speaks three operations
 * per direction instead of one framed byte blob:
 *   host -> JS : open(handle,id,command) / data(handle,id,bytes,off,len) / closePipe(handle,id,reason)
 *                -> JS globals  __jsOpen(id,cmd) / __jsData(id,ab) / __jsClose(id,reason)
 *   JS -> host : JS globals  __hostOpen(id,cmd) / __hostData(id,u8,off,len) / __hostClose(id,reason)
 *                -> static  QuickJsNative.onOpen/onData/onClose(handle, ...)
 * plus create/evalSync/close and the microtask pump. ALL routing (which pipe, streaming) lives in
 * Kotlin and JS; C only shuttles (id, command|bytes|reason). No per-frame header is built.
 *
 * Strings that cross (commands, reasons, eval source/result, errors) use standard UTF-8 <-> UTF-16
 * (NewString/GetStringChars), never NewStringUTF, so astral characters survive. Payloads are raw
 * bytes. Single-thread confinement: every JNI entry point runs on the one Kotlin engine thread.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "quickjs.h"
#include "quickjs_json.h"

typedef struct Engine {
    JSRuntime *rt;
    JSContext *ctx;
    JavaVM *jvm;
    jclass bridgeClass;  /* global ref to ymusic.quickjs.QuickJsNative */
    jmethodID onOpen;    /* static void onOpen(long, int, String) */
    jmethodID onData;    /* static void onData(long, int, byte[]) */
    jmethodID onClose;   /* static void onClose(long, int, String) */
    JSValue jsOpen, jsData, jsClose; /* JS mux entry points (C -> JS) */
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

/* ---- inbound: JS -> host (JS calls these) ---- */

/* __hostOpen(id, command) */
static JSValue js_host_open(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
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

/* __hostData(id, u8, off, len) */
static JSValue js_host_data(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    JNIEnv *env = engine_env(e);
    uint32_t id = 0;
    int64_t off = 0, len = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    JS_ToInt64(ctx, &off, argv[2]);
    JS_ToInt64(ctx, &len, argv[3]);
    size_t ta_off = 0, ta_len = 0, bpe = 0;
    JSValue ab = JS_GetTypedArrayBuffer(ctx, argv[1], &ta_off, &ta_len, &bpe);
    if (!JS_IsException(ab)) {
        size_t sz = 0;
        uint8_t *ptr = JS_GetArrayBuffer(ctx, &sz, ab);
        if (ptr && len > 0) {
            jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
            (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)(ptr + ta_off + off));
            (*env)->CallStaticVoidMethod(env, e->bridgeClass, e->onData, (jlong)(intptr_t)e, (jint)id, arr);
            (*env)->DeleteLocalRef(env, arr);
        }
    }
    JS_FreeValue(ctx, ab);
    return JS_UNDEFINED;
}

/* __hostClose(id, reason)  — reason null/undefined = END, string = RESET */
static JSValue js_host_close(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *e = (Engine *)JS_GetContextOpaque(ctx);
    JNIEnv *env = engine_env(e);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    jstring jr = NULL;
    if (!JS_IsNull(argv[1]) && !JS_IsUndefined(argv[1])) {
        size_t len = 0;
        const char *reason = JS_ToCStringLen(ctx, &len, argv[1]);
        jr = utf8_to_jstring(env, reason ? reason : "", reason ? len : 0);
        if (reason) JS_FreeCString(ctx, reason);
    }
    (*env)->CallStaticVoidMethod(env, e->bridgeClass, e->onClose, (jlong)(intptr_t)e, (jint)id, jr);
    if (jr) (*env)->DeleteLocalRef(env, jr);
    return JS_UNDEFINED;
}

/* ---- JNI entry points (all on the single engine thread) ---- */

JNIEXPORT jlong JNICALL
Java_ymusic_quickjs_QuickJsNative_create(JNIEnv *env, jobject thiz, jstring jbootstrap) {
    Engine *e = (Engine *)calloc(1, sizeof(Engine));
    e->rt = JS_NewRuntime();
    e->ctx = JS_NewContext(e->rt);
    e->jsOpen = e->jsData = e->jsClose = JS_UNDEFINED;
    JS_SetContextOpaque(e->ctx, e);
    (*env)->GetJavaVM(env, &e->jvm);

    jclass local = (*env)->FindClass(env, "ymusic/quickjs/QuickJsNative");
    e->bridgeClass = (jclass)(*env)->NewGlobalRef(env, local);
    e->onOpen = (*env)->GetStaticMethodID(env, e->bridgeClass, "onOpen", "(JILjava/lang/String;)V");
    e->onData = (*env)->GetStaticMethodID(env, e->bridgeClass, "onData", "(JI[B)V");
    e->onClose = (*env)->GetStaticMethodID(env, e->bridgeClass, "onClose", "(JILjava/lang/String;)V");

    JSValue global = JS_GetGlobalObject(e->ctx);
    JS_SetPropertyStr(e->ctx, global, "__hostOpen", JS_NewCFunction(e->ctx, js_host_open, "__hostOpen", 2));
    JS_SetPropertyStr(e->ctx, global, "__hostData", JS_NewCFunction(e->ctx, js_host_data, "__hostData", 4));
    JS_SetPropertyStr(e->ctx, global, "__hostClose", JS_NewCFunction(e->ctx, js_host_close, "__hostClose", 2));
    JS_FreeValue(e->ctx, global);
    qjs_register_json(e->ctx);

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

/* Host -> JS: call one of the JS mux entry points. */
static void call_js(Engine *e, JSValue fn, int argc, JSValue *argv) {
    JSValue g = JS_GetGlobalObject(e->ctx);
    JSValue r = JS_Call(e->ctx, fn, g, argc, argv);
    JS_FreeValue(e->ctx, r);
    JS_FreeValue(e->ctx, g);
    for (int i = 0; i < argc; i++) JS_FreeValue(e->ctx, argv[i]);
    pump_jobs(e);
}

JNIEXPORT void JNICALL
Java_ymusic_quickjs_QuickJsNative_open(JNIEnv *env, jobject thiz, jlong handle, jint id, jstring jcommand) {
    Engine *e = (Engine *)(intptr_t)handle;
    size_t len = 0;
    char *cmd = jstring_to_utf8(env, jcommand, &len);
    JSValue argv[2] = { JS_NewUint32(e->ctx, (uint32_t)id), JS_NewStringLen(e->ctx, cmd, len) };
    free(cmd);
    call_js(e, e->jsOpen, 2, argv);
}

JNIEXPORT void JNICALL
Java_ymusic_quickjs_QuickJsNative_data(JNIEnv *env, jobject thiz, jlong handle, jint id,
                                       jbyteArray bytes, jint off, jint len) {
    Engine *e = (Engine *)(intptr_t)handle;
    jbyte *elems = (*env)->GetByteArrayElements(env, bytes, NULL);
    JSValue ab = JS_NewArrayBufferCopy(e->ctx, (const uint8_t *)elems + off, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, bytes, elems, JNI_ABORT);
    JSValue argv[2] = { JS_NewUint32(e->ctx, (uint32_t)id), ab };
    call_js(e, e->jsData, 2, argv);
}

JNIEXPORT void JNICALL
Java_ymusic_quickjs_QuickJsNative_closePipe(JNIEnv *env, jobject thiz, jlong handle, jint id, jstring jreason) {
    Engine *e = (Engine *)(intptr_t)handle;
    JSValue rv;
    if (jreason == NULL) {
        rv = JS_NULL;
    } else {
        size_t len = 0;
        char *reason = jstring_to_utf8(env, jreason, &len);
        rv = JS_NewStringLen(e->ctx, reason, len);
        free(reason);
    }
    JSValue argv[2] = { JS_NewUint32(e->ctx, (uint32_t)id), rv };
    call_js(e, e->jsClose, 2, argv);
}

JNIEXPORT void JNICALL
Java_ymusic_quickjs_QuickJsNative_close(JNIEnv *env, jobject thiz, jlong handle) {
    Engine *e = (Engine *)(intptr_t)handle;
    if (!e) return;
    JS_FreeValue(e->ctx, e->jsOpen);
    JS_FreeValue(e->ctx, e->jsData);
    JS_FreeValue(e->ctx, e->jsClose);
    (*env)->DeleteGlobalRef(env, e->bridgeClass);
    JS_FreeContext(e->ctx);
    JS_FreeRuntime(e->rt);
    free(e);
}
