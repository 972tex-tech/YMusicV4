/*
 * Format a JS value for the unhandled-rejection log. JSON.stringify of an Error is "{}"
 * (message/stack are non-enumerable); ToString is "Error: msg" with no frames. JS_PrintValue
 * dumps name + message + stack for Error objects. Non-Error reasons (reject({}), reject(1))
 * get a PrintValue dump plus a stack captured here — the tracker runs synchronously at
 * reject(), so that is the reject() call site.
 */
#ifndef QUICKJS_DUMP_H
#define QUICKJS_DUMP_H

#include "quickjs.h"
#include <stdlib.h>
#include <string.h>

typedef struct {
    char *buf;
    size_t len;
    size_t cap;
} QjsDumpBuf;

static void qjs_dump_write(void *opaque, const char *s, size_t n)
{
    QjsDumpBuf *d = (QjsDumpBuf *)opaque;
    if (!s || !n) return;
    if (d->len + n + 1 > d->cap) {
        size_t cap = d->cap ? d->cap : 256;
        while (cap < d->len + n + 1) cap *= 2;
        char *nb = (char *)realloc(d->buf, cap);
        if (!nb) return;
        d->buf = nb;
        d->cap = cap;
    }
    memcpy(d->buf + d->len, s, n);
    d->len += n;
    d->buf[d->len] = '\0';
}

static void qjs_dump_puts(QjsDumpBuf *d, const char *s)
{
    if (s) qjs_dump_write(d, s, strlen(s));
}

static void qjs_dump_swallow(JSContext *ctx)
{
    if (JS_HasException(ctx))
        JS_FreeValue(ctx, JS_GetException(ctx));
}

/* Append Error().stack captured at the tracker (reject() call site). */
static void qjs_dump_reject_site(JSContext *ctx, QjsDumpBuf *d)
{
    JSValue g = JS_GetGlobalObject(ctx);
    JSValue ctor = JS_GetPropertyStr(ctx, g, "Error");
    JS_FreeValue(ctx, g);
    if (JS_IsException(ctor)) {
        qjs_dump_swallow(ctx);
        return;
    }
    JSValue err = JS_CallConstructor(ctx, ctor, 0, NULL);
    JS_FreeValue(ctx, ctor);
    if (JS_IsException(err)) {
        qjs_dump_swallow(ctx);
        return;
    }
    JSValue stack = JS_GetPropertyStr(ctx, err, "stack");
    JS_FreeValue(ctx, err);
    if (JS_IsException(stack)) {
        qjs_dump_swallow(ctx);
        return;
    }
    if (!JS_IsUndefined(stack) && !JS_IsNull(stack)) {
        size_t sl = 0;
        const char *s = JS_ToCStringLen(ctx, &sl, stack);
        if (s) {
            if (sl) {
                qjs_dump_puts(d, "\n");
                qjs_dump_write(d, s, sl);
            }
            JS_FreeCString(ctx, s);
        } else {
            qjs_dump_swallow(ctx);
        }
    }
    JS_FreeValue(ctx, stack);
}

/* malloc'd UTF-8; caller frees. NULL if the dump could not be allocated. */
static char *qjs_format_rejection(JSContext *ctx, JSValueConst reason, size_t *out_len)
{
    QjsDumpBuf d = {0};
    JSPrintValueOptions opt;
    JS_PrintValueSetDefaultOptions(&opt);
    opt.max_depth = 4;
    opt.max_string_length = 4000;
    opt.max_item_count = 50;
    /* Error already pretty-prints name/message/stack; for plain objects, include
     * non-enumerable own fields (DOMException.code, etc.). */
    if (!JS_IsError(ctx, reason))
        opt.show_hidden = 1;
    JS_PrintValue(ctx, qjs_dump_write, &d, reason, &opt);
    qjs_dump_swallow(ctx);

    if (JS_IsError(ctx, reason)) {
        JSValue cause = JS_GetPropertyStr(ctx, reason, "cause");
        if (JS_IsException(cause)) {
            qjs_dump_swallow(ctx);
        } else if (!JS_IsUndefined(cause) && !JS_IsNull(cause)) {
            qjs_dump_puts(&d, "\ncause: ");
            JS_PrintValue(ctx, qjs_dump_write, &d, cause, &opt);
            qjs_dump_swallow(ctx);
            JS_FreeValue(ctx, cause);
        } else {
            JS_FreeValue(ctx, cause);
        }
    } else {
        qjs_dump_reject_site(ctx, &d);
    }

    if (!d.buf) {
        *out_len = 0;
        return NULL;
    }
    *out_len = d.len;
    return d.buf;
}

#endif
