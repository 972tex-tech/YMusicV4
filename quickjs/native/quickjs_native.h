/*
 * Plain-C QuickJS transport for the Kotlin/Native (cinterop) path — the non-JNI counterpart of
 * quickjs_jni.c. The pipe multiplexer speaks three operations per direction (open/data/close)
 * rather than one framed byte blob, so no per-frame header is allocated and command/reason cross
 * as native strings. Strings are UTF-8 (Kotlin/Native's toKString/cstr are UTF-8, astral-correct).
 */
#ifndef QUICKJS_NATIVE_H
#define QUICKJS_NATIVE_H

#include <stddef.h>
#include <stdint.h>

typedef struct QjsEngine QjsEngine;

/* op codes for the inbound (JS->host) callback */
#define QJS_OP_OPEN  0
#define QJS_OP_DATA  1
#define QJS_OP_CLOSE 2

/* Invoked when JS reaches the host. For OPEN: data = command (NUL-terminated UTF-8), len = its
 * length. For DATA: data = raw payload bytes, len = length. For CLOSE: data = reason
 * (NUL-terminated UTF-8) or NULL for a normal half-close (END); len = reason length or 0. */
typedef void (*QjsInboundCb)(void *user, int op, uint32_t id, const uint8_t *data, size_t len);

/* Create an engine, install the JS transport, and eval [bootstrap] (comment-stripped JS source).
 * Returns NULL on failure with *err set to a malloc'd message (free via qjs_free). */
QjsEngine *qjs_create(const char *bootstrap, QjsInboundCb cb, void *user, char **err);

/* Evaluate [script]; returns a malloc'd UTF-8 string (the result's string form, or the error
 * message) or NULL. *isError is set to 1 when the returned string is an error message. */
char *qjs_eval_sync(QjsEngine *e, const char *script, const char *filename, int *isError);

/* Host -> JS: open a pipe [id] addressed to [command]; stream [data] bytes; close (reason=NULL is
 * a normal half-close/END, non-NULL is an error/RESET). */
void qjs_open(QjsEngine *e, uint32_t id, const char *command);
void qjs_data(QjsEngine *e, uint32_t id, const uint8_t *data, size_t len);
void qjs_close_pipe(QjsEngine *e, uint32_t id, const char *reason);

void qjs_close(QjsEngine *e);
void qjs_free(void *p);

#endif
