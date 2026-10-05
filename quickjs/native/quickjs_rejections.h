#ifndef QUICKJS_REJECTIONS_H
#define QUICKJS_REJECTIONS_H

/* Unhandled-rejection timing, browser style. QuickJS reports a rejection the moment it happens and
 * retracts it when a handler attaches — so `Promise.reject(x).catch(f)` reports, then retracts.
 * Hold each report until the job queue has drained and emit only what is still unhandled. The
 * message is formatted at report time: the reject-site stack exists only then. */

#include <stdlib.h>
#include <string.h>

#include "quickjs.h"
#include "quickjs_dump.h"

typedef struct {
    JSValue promise;
    char *msg; /* NULL = unprintable reason */
    size_t len;
} QjsRejection;

typedef struct {
    QjsRejection *items;
    int count;
    int cap;
} QjsRejections;

static void qjs_rejection_free(JSContext *ctx, QjsRejection *r) {
    JS_FreeValue(ctx, r->promise);
    free(r->msg);
}

static void qjs_rejections_track(JSContext *ctx, QjsRejections *list, JSValueConst promise,
                                 JSValueConst reason, int is_handled) {
    if (is_handled) {
        for (int i = 0; i < list->count; i++) {
            if (JS_VALUE_GET_PTR(list->items[i].promise) != JS_VALUE_GET_PTR(promise)) continue;
            qjs_rejection_free(ctx, &list->items[i]);
            memmove(&list->items[i], &list->items[i + 1],
                    (size_t)(list->count - i - 1) * sizeof(QjsRejection));
            list->count--;
            return;
        }
        return;
    }
    if (list->count == list->cap) {
        int cap = list->cap ? list->cap * 2 : 8;
        QjsRejection *items = (QjsRejection *)realloc(list->items, (size_t)cap * sizeof(QjsRejection));
        if (!items) return;
        list->items = items;
        list->cap = cap;
    }
    QjsRejection *r = &list->items[list->count++];
    r->promise = JS_DupValue(ctx, promise);
    r->len = 0;
    r->msg = qjs_format_rejection(ctx, reason, &r->len);
}

/* Emit every still-unhandled rejection, oldest first. The list is detached first: report() may
 * run code that tracks new rejections. */
static void qjs_rejections_flush(JSContext *ctx, QjsRejections *list,
                                 void (*report)(void *user, const char *msg, size_t len), void *user) {
    if (list->count == 0) return;
    QjsRejection *items = list->items;
    int count = list->count;
    list->items = NULL;
    list->count = 0;
    list->cap = 0;
    for (int i = 0; i < count; i++) {
        if (items[i].msg) report(user, items[i].msg, items[i].len);
        else report(user, "(unprintable reason)", 20);
        qjs_rejection_free(ctx, &items[i]);
    }
    free(items);
}

/* Teardown: drop pending entries unreported (their promises must be freed before the runtime). */
static void qjs_rejections_free(JSContext *ctx, QjsRejections *list) {
    for (int i = 0; i < list->count; i++) qjs_rejection_free(ctx, &list->items[i]);
    free(list->items);
    list->items = NULL;
    list->count = 0;
    list->cap = 0;
}

#endif
