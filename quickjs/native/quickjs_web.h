#ifndef QUICKJS_WEB_H
#define QUICKJS_WEB_H

#include "quickjs.h"

#ifdef __cplusplus
extern "C" {
#endif

/* WHATWG atob / btoa / TextEncoder / TextDecoder as C functions (V8 native toString). */
void qjs_register_web(JSContext *ctx);
/* Frees the BotGuard recording row of ctx; call before JS_FreeContext. */
void qjs_web_release(JSContext *ctx);

#ifdef __cplusplus
}
#endif

#endif
