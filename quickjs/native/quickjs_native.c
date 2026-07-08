#include "quickjs_native.h"
#include "quickjs.h"
#include "quickjs_json.h"
#include "quickjs_pipe.h"
#include <stdlib.h>
#include <string.h>

struct QjsEngine {
    JSRuntime *rt;
    JSContext *ctx;
    QjsInboundCb cb;
    void *user;
    QjsPipeCtx *pipe;
    JSValue jsOpen, jsClose, jsCloseRead, jsDrain; /* JS mux entry points (C -> JS) */
};

/* Inbound flow control: the C pipe buffer drained below low-water; resume the host's parked
 * writer. Fired from JS-initiated read paths only (see quickjs_pipe.h). */
static int drain_hook(void *user, uint32_t id) {
    QjsEngine *e = (QjsEngine *)user;
    return e->cb ? e->cb(e->user, QJS_OP_DRAIN, id, NULL, 0) : 0;
}

static void pump(QjsEngine *e) {
    JSContext *c;
    while (JS_ExecutePendingJob(e->rt, &c) > 0) { /* microtasks */ }
}

/* ---- inbound: JS -> host (JS calls these) ---- */

/* __hostOpen(id, command) -> 0 accepted | -1 refused (no handler / host callback failed) */
static JSValue js_host_open(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsEngine *e = (QjsEngine *)JS_GetContextOpaque(ctx);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    size_t len = 0;
    const char *cmd = JS_ToCStringLen(ctx, &len, argv[1]);
    int rc = -1;
    if (e->cb) rc = e->cb(e->user, QJS_OP_OPEN, id, (const uint8_t *)(cmd ? cmd : ""), cmd ? len : 0);
    if (cmd) JS_FreeCString(ctx, cmd);
    return JS_NewInt32(ctx, rc);
}

/* __hostData(id, u8, off, len) -> accepted byte count (or -1 EPIPE). The payload pointer is only
 * valid during this call; the Kotlin mux consumes it synchronously (parked reader / okio buffer). */
static JSValue js_host_data(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsEngine *e = (QjsEngine *)JS_GetContextOpaque(ctx);
    uint32_t id = 0;
    int64_t off = 0, len = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    JS_ToInt64(ctx, &off, argv[2]);
    JS_ToInt64(ctx, &len, argv[3]);
    int rc = -1;
    size_t ta_off = 0, ta_len = 0, bpe = 0;
    JSValue ab = JS_GetTypedArrayBuffer(ctx, argv[1], &ta_off, &ta_len, &bpe);
    if (!JS_IsException(ab)) {
        /* ta_len is the view's BYTE length. off/len come from (remotely updatable) JS — an
         * unchecked range hands the host a pointer past the array: out-of-bounds read. */
        if (off < 0 || len < 0 || (uint64_t)off + (uint64_t)len > (uint64_t)ta_len) {
            JS_FreeValue(ctx, ab);
            return JS_ThrowRangeError(ctx, "__hostData range out of bounds");
        }
        size_t sz = 0;
        uint8_t *ptr = JS_GetArrayBuffer(ctx, &sz, ab);
        if (ptr && e->cb && len > 0) rc = e->cb(e->user, QJS_OP_WRITE, id, ptr + ta_off + off, (size_t)len);
        else rc = 0;
    }
    JS_FreeValue(ctx, ab);
    return JS_NewInt32(ctx, rc);
}

/* __hostClose(id, reason)  — write-side half-close: reason null/undefined = END, string = RESET */
static JSValue js_host_close(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsEngine *e = (QjsEngine *)JS_GetContextOpaque(ctx);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    const char *reason = NULL;
    size_t rlen = 0;
    if (argc > 1 && !JS_IsNull(argv[1]) && !JS_IsUndefined(argv[1]))
        reason = JS_ToCStringLen(ctx, &rlen, argv[1]);
    /* The trampoline returns 0 on success, -1 when the host callback threw. Unlike open/data the
     * return here isn't a protocol value, so a failure would vanish — raise it as a JS exception so
     * the bootstrap's failure paths engage (mirrors the JNI check_java_exception path). */
    int rc = 0;
    if (e->cb) rc = e->cb(e->user, QJS_OP_CLOSE_WRITE, id, (const uint8_t *)reason, rlen);
    if (reason) JS_FreeCString(ctx, reason);
    if (rc < 0) return JS_ThrowInternalError(ctx, "host callback failed (closeWrite)");
    return JS_UNDEFINED;
}

/* __hostCloseRead(id) — abandon the read end: the host's next write gets EPIPE */
static JSValue js_host_close_read(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsEngine *e = (QjsEngine *)JS_GetContextOpaque(ctx);
    uint32_t id = 0;
    JS_ToUint32(ctx, &id, argv[0]);
    int rc = 0;
    if (e->cb) rc = e->cb(e->user, QJS_OP_CLOSE_READ, id, NULL, 0);
    if (rc < 0) return JS_ThrowInternalError(ctx, "host callback failed (closeRead)");
    return JS_UNDEFINED;
}

QjsEngine *qjs_create(const char *bootstrap, QjsInboundCb cb, void *user, char **err) {
    QjsEngine *e = (QjsEngine *)calloc(1, sizeof(QjsEngine));
    e->rt = JS_NewRuntime();
    e->ctx = JS_NewContext(e->rt);
    e->cb = cb;
    e->user = user;
    e->jsOpen = e->jsClose = e->jsCloseRead = e->jsDrain = JS_UNDEFINED;
    JS_SetContextOpaque(e->ctx, e);

    JSValue g = JS_GetGlobalObject(e->ctx);
    JS_SetPropertyStr(e->ctx, g, "__hostOpen", JS_NewCFunction(e->ctx, js_host_open, "__hostOpen", 2));
    JS_SetPropertyStr(e->ctx, g, "__hostData", JS_NewCFunction(e->ctx, js_host_data, "__hostData", 4));
    JS_SetPropertyStr(e->ctx, g, "__hostClose", JS_NewCFunction(e->ctx, js_host_close, "__hostClose", 2));
    JS_SetPropertyStr(e->ctx, g, "__hostCloseRead", JS_NewCFunction(e->ctx, js_host_close_read, "__hostCloseRead", 1));
    JS_FreeValue(e->ctx, g);
    qjs_register_json(e->ctx);
    e->pipe = qjs_register_pipe(e->ctx, drain_hook, e);

    JSValue r = JS_Eval(e->ctx, bootstrap, strlen(bootstrap), "<bootstrap>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(r)) {
        JSValue exc = JS_GetException(e->ctx);
        const char *m = JS_ToCString(e->ctx, exc);
        if (err) *err = strdup(m ? m : "bootstrap failed");
        if (m) JS_FreeCString(e->ctx, m);
        JS_FreeValue(e->ctx, exc);
        JS_FreeValue(e->ctx, r);
        qjs_pipe_ctx_release(e->pipe, e->ctx);
        JS_FreeContext(e->ctx);
        JS_FreeRuntime(e->rt);
        qjs_pipe_ctx_free(e->pipe);
        free(e);
        return NULL;
    }
    JS_FreeValue(e->ctx, r);

    JSValue g2 = JS_GetGlobalObject(e->ctx);
    e->jsOpen = JS_GetPropertyStr(e->ctx, g2, "__jsOpen");
    e->jsClose = JS_GetPropertyStr(e->ctx, g2, "__jsClose");
    e->jsCloseRead = JS_GetPropertyStr(e->ctx, g2, "__jsCloseRead");
    e->jsDrain = JS_GetPropertyStr(e->ctx, g2, "__jsDrain");
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

/* Call [fn]; return its result as an int (or the given default on exception). Frees argv. Runs the
 * microtask pump afterward. */
static int call_js(QjsEngine *e, JSValue fn, int argc, JSValue *argv, int exc_rc) {
    JSValue g = JS_GetGlobalObject(e->ctx);
    JSValue r = JS_Call(e->ctx, fn, g, argc, argv);
    int rc = exc_rc;
    if (!JS_IsException(r)) JS_ToInt32(e->ctx, &rc, r);
    else JS_FreeValue(e->ctx, JS_GetException(e->ctx)); /* bootstrap bug; don't leave it pending */
    JS_FreeValue(e->ctx, r);
    JS_FreeValue(e->ctx, g);
    for (int i = 0; i < argc; i++) JS_FreeValue(e->ctx, argv[i]);
    pump(e);
    return rc;
}

int qjs_open(QjsEngine *e, uint32_t id, const char *command) {
    JSValue argv[2] = { JS_NewUint32(e->ctx, id), JS_NewString(e->ctx, command ? command : "") };
    return call_js(e, e->jsOpen, 2, argv, -1);
}

int qjs_write(QjsEngine *e, uint32_t id, const uint8_t *data, size_t len) {
    if (len == 0) return 0;
    int rc = qjs_pipe_write(e->pipe, id, data, len);
    pump(e); /* a settled read's reactions run now, after the caller's memory was consumed */
    return rc;
}

void qjs_close_write(QjsEngine *e, uint32_t id, const char *reason) {
    qjs_pipe_close_write(e->pipe, id, reason, reason ? strlen(reason) : 0);
    JSValue argv[1] = { JS_NewUint32(e->ctx, id) };
    call_js(e, e->jsClose, 1, argv, 0); /* JS-side bookkeeping (reap) */
}

void qjs_close_read(QjsEngine *e, uint32_t id) {
    JSValue argv[1] = { JS_NewUint32(e->ctx, id) };
    call_js(e, e->jsCloseRead, 1, argv, 0);
}

void qjs_drain(QjsEngine *e, uint32_t id) {
    JSValue argv[1] = { JS_NewUint32(e->ctx, id) };
    call_js(e, e->jsDrain, 1, argv, 0);
}

void qjs_close(QjsEngine *e) {
    if (!e) return;
    JS_FreeValue(e->ctx, e->jsOpen);
    JS_FreeValue(e->ctx, e->jsClose);
    JS_FreeValue(e->ctx, e->jsCloseRead);
    JS_FreeValue(e->ctx, e->jsDrain);
    qjs_pipe_ctx_release(e->pipe, e->ctx);
    JS_FreeContext(e->ctx);
    JS_FreeRuntime(e->rt); /* PipeBuf finalizers return segments to the pool -> free the ctx after */
    qjs_pipe_ctx_free(e->pipe);
    free(e);
}

void qjs_free(void *p) {
    free(p);
}
