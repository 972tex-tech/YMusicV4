#ifndef QUICKJS_HOSTFN_H
#define QUICKJS_HOSTFN_H

#include "quickjs.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Register global `__nativeFn(name, impl)` — a C trampoline whose toString is
 * V8-shaped native code and which forwards to the JS [impl]. */
void qjs_register_hostfn(JSContext *ctx);

#ifdef __cplusplus
}
#endif

#endif
