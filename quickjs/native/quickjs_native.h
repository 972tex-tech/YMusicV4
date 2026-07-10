/*
 * Plain-C QuickJS transport for the Kotlin/Native (cinterop) path — the non-JNI counterpart of
 * quickjs_jni.c. The pipe multiplexer speaks a few operations per direction rather than one framed
 * byte blob, so no per-frame header is allocated and commands/reasons cross as native strings.
 *
 * Each pipe direction is a bounded (64 KB), flow-controlled byte stream: write() copies its bytes
 * once into the C-owned pipe buffer (pooled 8 KB segments, see quickjs_pipe.h — no JS runs on the
 * write path) and returns the accepted byte count. accepted < len is backpressure: the writer
 * waits for a DRAIN notify. -1 means the reader closed its read end (EPIPE).
 * close_write(reason=NULL) is a clean END; a non-NULL reason is a RESET (the peer's read throws
 * instead of seeing EOF).
 *
 * Strings are UTF-8 (Kotlin/Native's toKString/cstr are UTF-8, astral-correct).
 */
#ifndef QUICKJS_NATIVE_H
#define QUICKJS_NATIVE_H

#include <stddef.h>
#include <stdint.h>

typedef struct QjsEngine QjsEngine;

/* op codes for the inbound (JS->host) callback */
#define QJS_OP_OPEN        0
#define QJS_OP_WRITE       1 /* data/len = payload (valid only during the call); return = accepted, -1 EPIPE */
#define QJS_OP_CLOSE_WRITE 2 /* data = RESET reason (NUL-terminated UTF-8) or NULL for END */
#define QJS_OP_CLOSE_READ  3 /* abandon read end: host's next write gets EPIPE */
#define QJS_OP_DRAIN       4 /* flow control: the reader drained; resume the parked writer */


/* Invoked when JS reaches the host. OPEN: data = command (NUL-terminated UTF-8), len = its
 * length — the return value is 0 (accepted) or -1 (refused: no handler / callback failed), so an
 * open of an unserved command fails AT the open, C-error-code style. WRITE: data = payload bytes,
 * len = length — consume synchronously (the pointer dies with the call); the return value is the
 * accepted byte count (or -1 EPIPE). CLOSE_WRITE: data = reason or NULL, len = reason length.
 * CLOSE_READ / DRAIN: data = NULL, len = 0. Return values of the other ops are ignored. */
typedef int (*QjsInboundCb)(void *user, int op, uint32_t id, const uint8_t *data, size_t len);

/* Create an engine, install the JS transport + pipe/JSON natives, and eval [script].
 * Returns NULL on failure with *err set to a malloc'd message (free via qjs_free). */
QjsEngine *qjs_create(const char *script, QjsInboundCb cb, void *user, char **err);

/* Compile [script] to serialized bytecode with per-function source text stripped
 * (Function.prototype.toString yields a placeholder; line-number debug info is kept so stack
 * traces stay useful). Returns a malloc'd buffer with *out_len set (free via qjs_free), or NULL
 * with *err set to a malloc'd message. */
uint8_t *qjs_compile(const char *script, const char *filename, size_t *out_len, char **err);

/* qjs_create from qjs_compile output. [prelude] (NULL/empty = none) is evaluated as plain source
 * before the bytecode program runs — for host-owned values unknown at compile time (`versions`). */
QjsEngine *qjs_create_bc(const uint8_t *bytecode, size_t len, const char *prelude,
                         QjsInboundCb cb, void *user, char **err);

/* Evaluate [script]; returns a malloc'd UTF-8 string (the result's string form, or the error
 * message) or NULL. *isError is set to 1 when the returned string is an error message. */
char *qjs_eval_sync(QjsEngine *e, const char *script, const char *filename, int *isError);

/* Host -> JS. open() returns 0 (accepted) or -1 (refused — no JS handler for the command);
 * write() returns the accepted byte count (see header comment); reason semantics as in
 * QJS_OP_CLOSE_WRITE; drain() resumes the JS side's parked partial send. */
int qjs_open(QjsEngine *e, uint32_t id, const char *command);
int qjs_write(QjsEngine *e, uint32_t id, const uint8_t *data, size_t len);
void qjs_close_write(QjsEngine *e, uint32_t id, const char *reason);
void qjs_close_read(QjsEngine *e, uint32_t id);
void qjs_drain(QjsEngine *e, uint32_t id);

void qjs_close(QjsEngine *e);
void qjs_free(void *p);

#endif
