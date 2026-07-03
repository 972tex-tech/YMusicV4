#include "quickjs_native.h"
#include "quickjs.h"
#include "quickjs_json.h"
#include <stdlib.h>
#include <string.h>

struct QjsEngine {
    JSRuntime *rt;
    JSContext *ctx;
    QjsInboundCb cb;
    void *user;
    JSValue jsOpen, jsData, jsClose; /* JS mux entry points (C -> JS) */
};

static void pump(QjsEngine *e) {
    JSContext *c;
    while (JS_ExecutePendingJob(e->rt, &c) > 0) { /* microtasks */ }
}

/* ---- inbound: JS -> host (JS calls these) ---- */

/* __hostOpen(id, command) */
static JSValue js_host_open(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsEngine *e = (QjsEngine *)JS_GetContextOpaque(ctx);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    size_t len = 0;
    const char *cmd = JS_ToCStringLen(ctx, &len, argv[1]);
    if (e->cb) e->cb(e->user, QJS_OP_OPEN, id, (const uint8_t *)(cmd ? cmd : ""), cmd ? len : 0);
    if (cmd) JS_FreeCString(ctx, cmd);
    return JS_UNDEFINED;
}

/* __hostData(id, u8, off, len) */
static JSValue js_host_data(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsEngine *e = (QjsEngine *)JS_GetContextOpaque(ctx);
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
        if (ptr && e->cb && len > 0) e->cb(e->user, QJS_OP_DATA, id, ptr + ta_off + off, (size_t)len);
    }
    JS_FreeValue(ctx, ab);
    return JS_UNDEFINED;
}

/* __hostClose(id, reason)  — reason null/undefined = END, string = RESET */
static JSValue js_host_close(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsEngine *e = (QjsEngine *)JS_GetContextOpaque(ctx);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    if (JS_IsNull(argv[1]) || JS_IsUndefined(argv[1])) {
        if (e->cb) e->cb(e->user, QJS_OP_CLOSE, id, NULL, 0);
    } else {
        size_t len = 0;
        const char *reason = JS_ToCStringLen(ctx, &len, argv[1]);
        if (e->cb) e->cb(e->user, QJS_OP_CLOSE, id, (const uint8_t *)(reason ? reason : ""), reason ? len : 0);
        if (reason) JS_FreeCString(ctx, reason);
    }
    return JS_UNDEFINED;
}

QjsEngine *qjs_create(const char *bootstrap, QjsInboundCb cb, void *user, char **err) {
    QjsEngine *e = (QjsEngine *)calloc(1, sizeof(QjsEngine));
    e->rt = JS_NewRuntime();
    e->ctx = JS_NewContext(e->rt);
    e->cb = cb;
    e->user = user;
    e->jsOpen = e->jsData = e->jsClose = JS_UNDEFINED;
    JS_SetContextOpaque(e->ctx, e);

    JSValue g = JS_GetGlobalObject(e->ctx);
    JS_SetPropertyStr(e->ctx, g, "__hostOpen", JS_NewCFunction(e->ctx, js_host_open, "__hostOpen", 2));
    JS_SetPropertyStr(e->ctx, g, "__hostData", JS_NewCFunction(e->ctx, js_host_data, "__hostData", 4));
    JS_SetPropertyStr(e->ctx, g, "__hostClose", JS_NewCFunction(e->ctx, js_host_close, "__hostClose", 2));
    JS_FreeValue(e->ctx, g);
    qjs_register_json(e->ctx);

    JSValue r = JS_Eval(e->ctx, bootstrap, strlen(bootstrap), "<bootstrap>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(r)) {
        JSValue exc = JS_GetException(e->ctx);
        const char *m = JS_ToCString(e->ctx, exc);
        if (err) *err = strdup(m ? m : "bootstrap failed");
        if (m) JS_FreeCString(e->ctx, m);
        JS_FreeValue(e->ctx, exc);
        JS_FreeValue(e->ctx, r);
        JS_FreeContext(e->ctx);
        JS_FreeRuntime(e->rt);
        free(e);
        return NULL;
    }
    JS_FreeValue(e->ctx, r);

    JSValue g2 = JS_GetGlobalObject(e->ctx);
    e->jsOpen = JS_GetPropertyStr(e->ctx, g2, "__jsOpen");
    e->jsData = JS_GetPropertyStr(e->ctx, g2, "__jsData");
    e->jsClose = JS_GetPropertyStr(e->ctx, g2, "__jsClose");
    JS_FreeValue(e->ctx, g2);
    return e;
}

char *qjs_eval_sync(QjsEngine *e, const char *script, const char *filename, int *isError) {
    JSValue val = JS_Eval(e->ctx, script, strlen(script), filename ? filename : "<eval>", JS_EVAL_TYPE_GLOBAL);
    pump(e);
    if (JS_IsException(val)) {
        JSValue exc = JS_GetException(e->ctx);
        const char *m = JS_ToCString(e->ctx, exc);
        char *out = strdup(m ? m : "error");
        if (m) JS_FreeCString(e->ctx, m);
        JS_FreeValue(e->ctx, exc);
        JS_FreeValue(e->ctx, val);
        if (isError) *isError = 1;
        return out;
    }
    char *out = NULL;
    if (!JS_IsUndefined(val) && !JS_IsNull(val)) {
        const char *res = JS_ToCString(e->ctx, val);
        if (res) { out = strdup(res); JS_FreeCString(e->ctx, res); }
    }
    JS_FreeValue(e->ctx, val);
    if (isError) *isError = 0;
    return out;
}

/* ---- outbound: host -> JS (call the JS mux entry points) ---- */

static void call_js(QjsEngine *e, JSValue fn, int argc, JSValue *argv) {
    JSValue g = JS_GetGlobalObject(e->ctx);
    JSValue r = JS_Call(e->ctx, fn, g, argc, argv);
    JS_FreeValue(e->ctx, r);
    JS_FreeValue(e->ctx, g);
    for (int i = 0; i < argc; i++) JS_FreeValue(e->ctx, argv[i]);
    pump(e);
}

void qjs_open(QjsEngine *e, uint32_t id, const char *command) {
    JSValue argv[2] = { JS_NewUint32(e->ctx, id), JS_NewString(e->ctx, command ? command : "") };
    call_js(e, e->jsOpen, 2, argv);
}

void qjs_data(QjsEngine *e, uint32_t id, const uint8_t *data, size_t len) {
    JSValue argv[2] = { JS_NewUint32(e->ctx, id), JS_NewArrayBufferCopy(e->ctx, data, len) };
    call_js(e, e->jsData, 2, argv);
}

void qjs_close_pipe(QjsEngine *e, uint32_t id, const char *reason) {
    JSValue argv[2] = { JS_NewUint32(e->ctx, id), reason ? JS_NewString(e->ctx, reason) : JS_NULL };
    call_js(e, e->jsClose, 2, argv);
}

void qjs_close(QjsEngine *e) {
    if (!e) return;
    JS_FreeValue(e->ctx, e->jsOpen);
    JS_FreeValue(e->ctx, e->jsData);
    JS_FreeValue(e->ctx, e->jsClose);
    JS_FreeContext(e->ctx);
    JS_FreeRuntime(e->rt);
    free(e);
}

void qjs_free(void *p) {
    free(p);
}
