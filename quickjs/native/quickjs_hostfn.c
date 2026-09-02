#include "quickjs_hostfn.h"

#include <string.h>

/* Host APIs as C functions so Function.prototype.toString is V8 native. */

static JSValue js_native_trampoline(JSContext *ctx, JSValueConst this_val, int argc,
                                    JSValueConst *argv, int magic, JSValue *func_data)
{
    JSValue impl = func_data[0];
    (void)magic;
    if (JS_IsFunction(ctx, impl))
        return JS_Call(ctx, impl, this_val, argc, argv);
    return JS_UNDEFINED;
}

static JSValue wrap_native(JSContext *ctx, const char *name, JSValueConst impl, int length)
{
    JSValue data[1];
    JSValue fn, name_val;
    if (length < 0)
        length = 0;
    data[0] = impl;
    fn = JS_NewCFunctionData(ctx, js_native_trampoline, length, 0, 1, data);
    if (JS_IsException(fn))
        return fn;
    JS_SetConstructorBit(ctx, fn, 1);
    name_val = JS_NewString(ctx, name);
    JS_DefinePropertyValueStr(ctx, fn, "name", name_val, JS_PROP_CONFIGURABLE);
    return fn;
}

static JSValue js_native_fn(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    const char *name;
    JSValue fn;
    int length = 0;
    (void)this_val;
    if (argc < 1)
        return JS_ThrowTypeError(ctx, "__nativeFn(name, impl?)");
    name = JS_ToCString(ctx, argv[0]);
    if (!name)
        return JS_EXCEPTION;
    if (argc > 2) {
        if (JS_ToInt32(ctx, &length, argv[2])) {
            JS_FreeCString(ctx, name);
            return JS_EXCEPTION;
        }
    } else if (argc > 1 && JS_IsFunction(ctx, argv[1])) {
        JSValue lv = JS_GetPropertyStr(ctx, argv[1], "length");
        if (JS_IsException(lv)) {
            JS_FreeCString(ctx, name);
            return lv;
        }
        if (JS_ToInt32(ctx, &length, lv)) {
            JS_FreeValue(ctx, lv);
            JS_FreeCString(ctx, name);
            return JS_EXCEPTION;
        }
        JS_FreeValue(ctx, lv);
    }
    fn = wrap_native(ctx, name, argc > 1 ? argv[1] : JS_UNDEFINED, length);
    JS_FreeCString(ctx, name);
    return fn;
}

void qjs_register_hostfn(JSContext *ctx)
{
    JSValue g = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, g, "__nativeFn", JS_NewCFunction(ctx, js_native_fn, "__nativeFn", 3));
    JS_FreeValue(ctx, g);
}
