#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "quickjs_json.h"
#include "cutils.h"

/* ============================================================================================
 * Streaming JSON parser + native stringify.
 *
 * The point: parse a JSON body arriving in CHUNKS without ever holding the whole body in memory.
 * A pull-parser can't do this here — frames are delivered into JS by the same single engine thread
 * that would block waiting for them, so pulling deadlocks. Hence a PUSH parser: a resumable state
 * machine fed one chunk at a time, suspending mid-token at chunk boundaries, producing the value at
 * end. Each chunk is consumed and discarded — only the parse stack, the current partial token, and
 * the output value tree are held, never the raw bytes.
 *
 * JS surface (globalThis):
 *   __jsonNew(expect?)               -> parser object (opaque; freed on GC or after end). expect
 *                                       0=any, 1=object, 2=array (root type checked on the 1st byte).
 *   __jsonPush(parser, u8, off, len) -> bytes consumed. Stops at the end of the top-level value, so
 *                                       a shorter-than-len return means the value is done and the
 *                                       remaining bytes are tail data (e.g. "{json}tail").
 *   __jsonDone(parser)               -> bool: the top-level value is complete.
 *   __jsonEnd(parser)                -> value (throws on incomplete/malformed input).
 *   __parseJson(uint8array)          -> value. One-shot for when you already have all the bytes.
 *   __stringifyJson(value)           -> Uint8Array of UTF-8 JSON bytes (native encode mirror).
 * ============================================================================================ */

#define JP_MAX_DEPTH 512

/* required top-level type (checked on the root value's first byte) */
#define EXPECT_ANY    0
#define EXPECT_OBJECT 1
#define EXPECT_ARRAY  2

/* Tokenizer / structure states — each is a resumable suspend point across chunk boundaries. */
enum {
    S_VALUE,             /* expecting a value */
    S_OBJ_KEY_OR_END,    /* after '{' or ',' in object: expect '"' or '}' */
    S_OBJ_COLON,         /* after a key: expect ':' */
    S_OBJ_COMMA_OR_END,  /* after an object value: expect ',' or '}' */
    S_ARR_VALUE_OR_END,  /* after '[' or ',' in array: expect value or ']' */
    S_ARR_COMMA_OR_END,  /* after an array value: expect ',' or ']' */
    S_STR,               /* in a string body */
    S_STR_ESC,           /* after '\' */
    S_STR_U,             /* collecting \uXXXX hex */
    S_STR_SURR_BS,       /* high surrogate pending, expecting '\' */
    S_STR_SURR_U,        /* high surrogate pending, consumed '\', expecting 'u' */
    S_NUM,               /* in a number */
    S_LIT,               /* matching true/false/null */
    S_DONE               /* top-level value complete */
};

typedef struct {
    JSValue container;   /* JS object/array being built */
    JSAtom pending_key;  /* object: key awaiting its value, else JS_ATOM_NULL */
    uint32_t index;      /* array: next element index */
    int is_array;
} JSFrame;

typedef struct {
    JSContext *ctx;
    int expect;

    JSFrame *stack;
    int sp, scap;

    JSValue result;
    int have_result;
    int failed;

    int tstate;
    DynBuf tok;          /* the current string/number/key bytes */
    int str_is_key;
    uint32_t uni_acc;    /* \u accumulator */
    int uni_count;       /* hex digits collected */
    uint32_t hi;         /* pending high surrogate, 0 = none */

    const char *lit;     /* "true"/"false"/"null" */
    int lit_len, lit_pos;
    JSValue lit_val;

    uint64_t pos;
} JStream;

#define JERR(s, msg) do { \
        JS_ThrowSyntaxError((s)->ctx, "%s at position %llu", msg, (unsigned long long)(s)->pos); \
        (s)->failed = 1; return -1; \
    } while (0)

static int is_ws(uint8_t c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }

static void jstream_free(JStream *s) {
    if (s == NULL) return;
    JSContext *ctx = s->ctx;
    for (int i = 0; i < s->sp; i++) {
        JS_FreeValue(ctx, s->stack[i].container);
        if (s->stack[i].pending_key != JS_ATOM_NULL) JS_FreeAtom(ctx, s->stack[i].pending_key);
    }
    free(s->stack);
    if (s->have_result) JS_FreeValue(ctx, s->result);
    dbuf_free(&s->tok);
    free(s);
}

static JStream *jstream_alloc(JSContext *ctx, int expect) {
    JStream *s = (JStream *)calloc(1, sizeof(JStream));
    if (s == NULL) { JS_ThrowOutOfMemory(ctx); return NULL; }
    s->ctx = ctx;
    s->expect = expect;
    s->result = JS_UNDEFINED;
    s->lit_val = JS_UNDEFINED;
    dbuf_init(&s->tok);
    s->tstate = S_VALUE;
    return s;
}

static int tok_put_byte(JStream *s, uint8_t c) {
    if (dbuf_putc(&s->tok, c)) { JS_ThrowOutOfMemory(s->ctx); s->failed = 1; return -1; }
    return 0;
}
static int tok_put_cp(JStream *s, uint32_t cp) {
    uint8_t b[8];
    int n = unicode_to_utf8(b, cp);
    if (dbuf_put(&s->tok, b, n)) { JS_ThrowOutOfMemory(s->ctx); s->failed = 1; return -1; }
    return 0;
}

/* Attach a completed value to its destination (parent frame or the root result), set next state. */
static int st_finish_value(JStream *s, JSValue v) {
    JSContext *ctx = s->ctx;
    if (s->sp == 0) {
        s->result = v;
        s->have_result = 1;
        s->tstate = S_DONE;
        return 0;
    }
    JSFrame *f = &s->stack[s->sp - 1];
    if (f->is_array) {
        if (JS_DefinePropertyValueUint32(ctx, f->container, f->index++, v, JS_PROP_C_W_E) < 0) { s->failed = 1; return -1; }
        s->tstate = S_ARR_COMMA_OR_END;
    } else {
        int r = JS_DefinePropertyValue(ctx, f->container, f->pending_key, v, JS_PROP_C_W_E);
        JS_FreeAtom(ctx, f->pending_key);
        f->pending_key = JS_ATOM_NULL;
        if (r < 0) { s->failed = 1; return -1; }
        s->tstate = S_OBJ_COMMA_OR_END;
    }
    return 0;
}

static int st_open_container(JStream *s, int is_array) {
    if (s->sp >= JP_MAX_DEPTH) JERR(s, "nesting too deep");
    if (s->sp >= s->scap) {
        int ncap = s->scap ? s->scap * 2 : 16;
        JSFrame *ns = (JSFrame *)realloc(s->stack, sizeof(JSFrame) * ncap);
        if (ns == NULL) { JS_ThrowOutOfMemory(s->ctx); s->failed = 1; return -1; }
        s->stack = ns;
        s->scap = ncap;
    }
    JSFrame *f = &s->stack[s->sp++];
    f->is_array = is_array;
    f->pending_key = JS_ATOM_NULL;
    f->index = 0;
    f->container = is_array ? JS_NewArray(s->ctx) : JS_NewObject(s->ctx);
    if (JS_IsException(f->container)) { s->sp--; s->failed = 1; return -1; }
    s->tstate = is_array ? S_ARR_VALUE_OR_END : S_OBJ_KEY_OR_END;
    return 0;
}

static int st_close_container(JStream *s) {
    JSFrame f = s->stack[--s->sp];
    if (f.pending_key != JS_ATOM_NULL) JS_FreeAtom(s->ctx, f.pending_key);
    return st_finish_value(s, f.container);
}

/* Validate and parse tok as a JSON number. -1 syntax, -2 oom, 0 ok (*out set). Integers up to 18
 * digits become exact int64; everything else goes through strtod (same as JSON.parse). */
static int parse_number_tok(JSContext *ctx, const uint8_t *b, size_t n, JSValue *out) {
    size_t i = 0;
    int is_float = 0;
    if (i < n && b[i] == '-') i++;
    if (i >= n || b[i] < '0' || b[i] > '9') return -1;
    if (b[i] == '0') i++;
    else while (i < n && b[i] >= '0' && b[i] <= '9') i++;
    if (i < n && b[i] == '.') {
        is_float = 1; i++;
        if (i >= n || b[i] < '0' || b[i] > '9') return -1;
        while (i < n && b[i] >= '0' && b[i] <= '9') i++;
    }
    if (i < n && (b[i] == 'e' || b[i] == 'E')) {
        is_float = 1; i++;
        if (i < n && (b[i] == '+' || b[i] == '-')) i++;
        if (i >= n || b[i] < '0' || b[i] > '9') return -1;
        while (i < n && b[i] >= '0' && b[i] <= '9') i++;
    }
    if (i != n) return -1;
    if (!is_float && n <= 18) {
        int neg = b[0] == '-';
        int64_t v = 0;
        for (size_t k = neg; k < n; k++) v = v * 10 + (b[k] - '0');
        *out = JS_NewInt64(ctx, neg ? -v : v);
        return 0;
    }
    char sbuf[64], *cb = sbuf;
    if (n + 1 > sizeof(sbuf)) { cb = (char *)malloc(n + 1); if (cb == NULL) return -2; }
    memcpy(cb, b, n);
    cb[n] = '\0';
    double dv = strtod(cb, NULL);
    if (cb != sbuf) free(cb);
    *out = JS_NewFloat64(ctx, dv);
    return 0;
}

static int st_finish_number(JStream *s) {
    JSValue nv;
    int r = parse_number_tok(s->ctx, s->tok.buf, s->tok.size, &nv);
    if (r == -1) JERR(s, "invalid number");
    if (r == -2) { JS_ThrowOutOfMemory(s->ctx); s->failed = 1; return -1; }
    return st_finish_value(s, nv);
}

static int st_key_done(JStream *s) {
    JSFrame *top = &s->stack[s->sp - 1];
    top->pending_key = JS_NewAtomLen(s->ctx, (const char *)s->tok.buf, s->tok.size);
    if (top->pending_key == JS_ATOM_NULL) { s->failed = 1; return -1; }
    s->tstate = S_OBJ_COLON;
    return 0;
}

/* Emit the current \u code unit (uni_acc), honoring a pending high surrogate (WTF-8 for a lone one,
 * which JS_NewStringLen decodes back). */
static int st_emit_unit(JStream *s) {
    if (s->hi != 0) {
        if (s->uni_acc >= 0xDC00 && s->uni_acc <= 0xDFFF) {
            uint32_t cp = 0x10000 + ((s->hi - 0xD800) << 10) + (s->uni_acc - 0xDC00);
            s->hi = 0;
            if (tok_put_cp(s, cp)) return -1;
            s->tstate = S_STR;
            return 0;
        }
        if (tok_put_cp(s, s->hi)) return -1; /* lone high surrogate */
        s->hi = 0;
    }
    if (s->uni_acc >= 0xD800 && s->uni_acc <= 0xDBFF) {
        s->hi = s->uni_acc;
        s->tstate = S_STR_SURR_BS;
    } else {
        if (tok_put_cp(s, s->uni_acc)) return -1;
        s->tstate = S_STR;
    }
    return 0;
}

static int st_string_done(JStream *s) {
    if (s->str_is_key) return st_key_done(s);
    JSValue v = JS_NewStringLen(s->ctx, (const char *)s->tok.buf, s->tok.size);
    if (JS_IsException(v)) { s->failed = 1; return -1; }
    return st_finish_value(s, v);
}

static void st_begin_string(JStream *s, int is_key) {
    s->str_is_key = is_key;
    s->hi = 0;
    s->tok.size = 0;
    s->tstate = S_STR;
}

/* Feed one chunk. Parses at most ONE top-level value: stops as soon as the value completes (the
 * parser reaches S_DONE) and returns how many bytes of [data,+len) were consumed — the caller keeps
 * the rest (e.g. "{json}tail"). While the value is incomplete, consumes the whole chunk. Returns
 * the consumed byte count, or -1 with a JS exception pending. */
static int jstream_push(JStream *s, const uint8_t *data, size_t len) {
    if (s->failed) { JS_ThrowSyntaxError(s->ctx, "parser is in a failed state"); return -1; }
    const uint8_t *p = data, *end = data + len;
    while (p < end && s->tstate != S_DONE) {
        uint8_t c = *p;
        switch (s->tstate) {
        case S_VALUE:
            if (is_ws(c)) { p++; s->pos++; break; }
            // enforce the required root type on its first byte (sp==0 is only true for the root)
            if (s->expect != EXPECT_ANY && s->sp == 0) {
                if (s->expect == EXPECT_OBJECT && c != '{') JERR(s, "expected a JSON object");
                if (s->expect == EXPECT_ARRAY && c != '[') JERR(s, "expected a JSON array");
            }
            if (c == '{') { p++; s->pos++; if (st_open_container(s, 0)) return -1; break; }
            if (c == '[') { p++; s->pos++; if (st_open_container(s, 1)) return -1; break; }
            if (c == '"') { p++; s->pos++; st_begin_string(s, 0); break; }
            if (c == '-' || (c >= '0' && c <= '9')) { s->tok.size = 0; s->tstate = S_NUM; break; }
            if (c == 't') { s->lit = "true";  s->lit_len = 4; s->lit_pos = 0; s->lit_val = JS_TRUE;  s->tstate = S_LIT; break; }
            if (c == 'f') { s->lit = "false"; s->lit_len = 5; s->lit_pos = 0; s->lit_val = JS_FALSE; s->tstate = S_LIT; break; }
            if (c == 'n') { s->lit = "null";  s->lit_len = 4; s->lit_pos = 0; s->lit_val = JS_NULL;  s->tstate = S_LIT; break; }
            JERR(s, "unexpected character");

        case S_OBJ_KEY_OR_END:
            if (is_ws(c)) { p++; s->pos++; break; }
            if (c == '"') { p++; s->pos++; st_begin_string(s, 1); break; }
            if (c == '}') { p++; s->pos++; if (st_close_container(s)) return -1; break; }
            JERR(s, "expected string key or '}'");

        case S_OBJ_COLON:
            if (is_ws(c)) { p++; s->pos++; break; }
            if (c == ':') { p++; s->pos++; s->tstate = S_VALUE; break; }
            JERR(s, "expected ':'");

        case S_OBJ_COMMA_OR_END:
            if (is_ws(c)) { p++; s->pos++; break; }
            if (c == ',') { p++; s->pos++; s->tstate = S_OBJ_KEY_OR_END; break; }
            if (c == '}') { p++; s->pos++; if (st_close_container(s)) return -1; break; }
            JERR(s, "expected ',' or '}'");

        case S_ARR_VALUE_OR_END:
            if (is_ws(c)) { p++; s->pos++; break; }
            if (c == ']') { p++; s->pos++; if (st_close_container(s)) return -1; break; }
            s->tstate = S_VALUE; /* reprocess c as the element value */
            break;

        case S_ARR_COMMA_OR_END:
            if (is_ws(c)) { p++; s->pos++; break; }
            if (c == ',') { p++; s->pos++; s->tstate = S_ARR_VALUE_OR_END; break; }
            if (c == ']') { p++; s->pos++; if (st_close_container(s)) return -1; break; }
            JERR(s, "expected ',' or ']'");

        case S_STR:
            if (c == '"') { p++; s->pos++; if (st_string_done(s)) return -1; break; }
            if (c == '\\') { p++; s->pos++; s->tstate = S_STR_ESC; break; }
            if (c < 0x20) JERR(s, "unescaped control character in string");
            if (tok_put_byte(s, c)) return -1;
            p++; s->pos++;
            break;

        case S_STR_ESC: {
            uint32_t cp;
            switch (c) {
            case '"': case '\\': case '/': cp = c; break;
            case 'b': cp = 8; break;
            case 'f': cp = 12; break;
            case 'n': cp = 10; break;
            case 'r': cp = 13; break;
            case 't': cp = 9; break;
            case 'u': p++; s->pos++; s->uni_acc = 0; s->uni_count = 0; s->tstate = S_STR_U; goto next;
            default: JERR(s, "invalid escape");
            }
            if (tok_put_cp(s, cp)) return -1;
            p++; s->pos++; s->tstate = S_STR;
            next: break;
        }

        case S_STR_U: {
            int hv;
            if (c >= '0' && c <= '9') hv = c - '0';
            else if (c >= 'a' && c <= 'f') hv = c - 'a' + 10;
            else if (c >= 'A' && c <= 'F') hv = c - 'A' + 10;
            else JERR(s, "invalid \\u escape");
            s->uni_acc = (s->uni_acc << 4) | (uint32_t)hv;
            p++; s->pos++;
            if (++s->uni_count == 4) { if (st_emit_unit(s)) return -1; }
            break;
        }

        case S_STR_SURR_BS:
            if (c == '\\') { p++; s->pos++; s->tstate = S_STR_SURR_U; break; }
            if (tok_put_cp(s, s->hi)) return -1; /* no low follows: lone high surrogate */
            s->hi = 0;
            s->tstate = S_STR; /* reprocess c */
            break;

        case S_STR_SURR_U:
            if (c == 'u') { p++; s->pos++; s->uni_acc = 0; s->uni_count = 0; s->tstate = S_STR_U; break; }
            if (tok_put_cp(s, s->hi)) return -1;
            s->hi = 0;
            s->tstate = S_STR_ESC; /* the '\' was consumed; reprocess c as the escape char */
            break;

        case S_NUM:
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                if (tok_put_byte(s, c)) return -1;
                p++; s->pos++;
                break;
            }
            if (st_finish_number(s)) return -1; /* terminator c reprocessed in the new state */
            break;

        case S_LIT:
            if (c != (uint8_t)s->lit[s->lit_pos]) JERR(s, "invalid literal");
            p++; s->pos++;
            if (++s->lit_pos == s->lit_len) { if (st_finish_value(s, s->lit_val)) return -1; }
            break;

        default: /* S_DONE is excluded by the loop guard; anything else is a bug */
            JERR(s, "internal parser state error");
        }
    }
    return (int)(p - data);
}

/* Finish parsing at EOF; returns the value (transfers ownership) or JS_EXCEPTION. */
static JSValue jstream_end(JStream *s) {
    if (s->failed) return JS_ThrowSyntaxError(s->ctx, "parser is in a failed state");
    if (s->tstate == S_NUM) {
        if (st_finish_number(s)) return JS_EXCEPTION; /* no terminator: EOF ends the number */
    }
    if (s->tstate != S_DONE || s->sp != 0 || !s->have_result)
        return JS_ThrowSyntaxError(s->ctx, "unexpected end of JSON input");
    JSValue r = s->result;
    s->result = JS_UNDEFINED;
    s->have_result = 0;
    return r;
}

/* ---- JS class wrapping a JStream (opaque; finalizer frees it) ---- */

static JSClassID jstream_class_id;

static void jstream_class_finalizer(JSRuntime *rt, JSValue val) {
    JStream *s = JS_GetOpaque(val, jstream_class_id);
    if (s) jstream_free(s);
}

/* The parser holds refcounted JSValues (half-built containers, the result) that aren't reachable
 * through the normal object graph — the GC must be told, or the cycle collector / shutdown sweep
 * mis-accounts them (a debug build asserts on a non-empty gc_obj_list at JS_FreeRuntime). */
static void jstream_class_gc_mark(JSRuntime *rt, JSValueConst val, JS_MarkFunc *mark_func) {
    JStream *s = JS_GetOpaque(val, jstream_class_id);
    if (s == NULL) return;
    for (int i = 0; i < s->sp; i++) JS_MarkValue(rt, s->stack[i].container, mark_func);
    if (s->have_result) JS_MarkValue(rt, s->result, mark_func);
}

static JSClassDef jstream_class_def = {
    "JsonStream", .finalizer = jstream_class_finalizer, .gc_mark = jstream_class_gc_mark
};

/* __jsonNew(expect?) -> parser object. */
static JSValue js_json_new(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    int32_t expect = EXPECT_ANY;
    if (argc >= 1 && JS_ToInt32(ctx, &expect, argv[0])) return JS_EXCEPTION;
    JStream *s = jstream_alloc(ctx, expect);
    if (s == NULL) return JS_EXCEPTION;
    JSValue obj = JS_NewObjectClass(ctx, jstream_class_id);
    if (JS_IsException(obj)) { jstream_free(s); return obj; }
    JS_SetOpaque(obj, s);
    return obj;
}

/* __jsonPush(parser, u8, off, len) -> bytes consumed. */
static JSValue js_json_push(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    if (argc < 2) return JS_ThrowTypeError(ctx, "__jsonPush(parser, u8, off, len)");
    JStream *s = (JStream *)JS_GetOpaque2(ctx, argv[0], jstream_class_id);
    if (s == NULL) return JS_EXCEPTION;
    size_t ta_off = 0, ta_len = 0, bpe = 0;
    JSValue ab = JS_GetTypedArrayBuffer(ctx, argv[1], &ta_off, &ta_len, &bpe);
    if (JS_IsException(ab)) return ab;
    size_t sz = 0;
    uint8_t *base = JS_GetArrayBuffer(ctx, &sz, ab);
    JSValue ret;
    if (base == NULL) {
        ret = JS_ThrowTypeError(ctx, "__jsonPush: argument is not a byte buffer");
    } else {
        int64_t off = 0, len = (int64_t)ta_len;
        int ok = 1;
        if (argc >= 3 && JS_ToInt64(ctx, &off, argv[2])) ok = 0;
        if (ok && argc >= 4 && JS_ToInt64(ctx, &len, argv[3])) ok = 0;
        if (!ok) { ret = JS_EXCEPTION; }
        else if (off < 0 || len < 0 || (size_t)(off + len) > ta_len) {
            ret = JS_ThrowRangeError(ctx, "__jsonPush: offset/length out of range");
        } else {
            int consumed = len > 0 ? jstream_push(s, base + ta_off + off, (size_t)len) : 0;
            ret = consumed < 0 ? JS_EXCEPTION : JS_NewInt32(ctx, consumed);
        }
    }
    JS_FreeValue(ctx, ab);
    return ret;
}

/* __jsonDone(parser) -> bool. */
static JSValue js_json_done(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    if (argc < 1) return JS_ThrowTypeError(ctx, "__jsonDone(parser)");
    JStream *s = (JStream *)JS_GetOpaque2(ctx, argv[0], jstream_class_id);
    if (s == NULL) return JS_EXCEPTION;
    return JS_NewBool(ctx, s->tstate == S_DONE);
}

/* __jsonEnd(parser) -> value. */
static JSValue js_json_end(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    if (argc < 1) return JS_ThrowTypeError(ctx, "__jsonEnd(parser)");
    JStream *s = (JStream *)JS_GetOpaque2(ctx, argv[0], jstream_class_id);
    if (s == NULL) return JS_EXCEPTION;
    return jstream_end(s);
}

/* __parseJson(uint8array) -> value. One-shot QuickJS parse for when you already have all the bytes. */
static JSValue js_parse_json(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    if (argc < 1) return JS_ThrowTypeError(ctx, "__parseJson requires a Uint8Array");
    size_t off = 0, len = 0, bpe = 0;
    JSValue ab = JS_GetTypedArrayBuffer(ctx, argv[0], &off, &len, &bpe);
    if (JS_IsException(ab)) return ab;
    size_t sz = 0;
    uint8_t *ptr = JS_GetArrayBuffer(ctx, &sz, ab);
    JSValue result;
    if (ptr == NULL) {
        result = JS_ThrowTypeError(ctx, "__parseJson: argument is not a byte buffer");
    } else {
        /* JS_ParseJSON's tokenizer needs a trailing '\0' sentinel; the typed array isn't
         * NUL-terminated, so copy into a len+1 buffer to avoid reading past the end. */
        char *tmp = (char *)malloc(len + 1);
        if (tmp == NULL) {
            result = JS_ThrowOutOfMemory(ctx);
        } else {
            memcpy(tmp, ptr + off, len);
            tmp[len] = '\0';
            result = JS_ParseJSON(ctx, tmp, len, "<json>");
            free(tmp);
        }
    }
    JS_FreeValue(ctx, ab);
    return result;
}

/* __stringifyJson(value) -> Uint8Array of UTF-8 JSON bytes. Native stringify + native UTF-16->UTF-8
 * (JS_ToCStringLen) — the encode mirror; never runs through interpreted per-codepoint JS. */
static JSValue js_stringify_json(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    if (argc < 1) return JS_ThrowTypeError(ctx, "__stringifyJson requires a value");
    JSValue str = JS_JSONStringify(ctx, argv[0], JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(str)) return str;
    if (JS_IsUndefined(str)) {
        JS_FreeValue(ctx, str);
        return JS_ThrowTypeError(ctx, "__stringifyJson: value has no JSON representation");
    }
    size_t len = 0;
    const char *utf8 = JS_ToCStringLen(ctx, &len, str);
    JS_FreeValue(ctx, str);
    if (utf8 == NULL) return JS_EXCEPTION;
    JSValue ab = JS_NewArrayBufferCopy(ctx, (const uint8_t *)utf8, len);
    JS_FreeCString(ctx, utf8);
    if (JS_IsException(ab)) return ab;
    /* new Uint8Array(ab) — the constructor reads argv[1]/argv[2] unconditionally (interpreter
     * undefined-padding), so pad them explicitly. View, no copy. */
    JSValueConst args[3] = { ab, JS_UNDEFINED, JS_UNDEFINED };
    JSValue u8 = JS_NewTypedArray(ctx, 3, args, JS_TYPED_ARRAY_UINT8);
    JS_FreeValue(ctx, ab);
    return u8;
}

void qjs_register_json(JSContext *ctx) {
    JS_NewClassID(&jstream_class_id);
    JS_NewClass(JS_GetRuntime(ctx), jstream_class_id, &jstream_class_def);

    JSValue global = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, global, "__parseJson", JS_NewCFunction(ctx, js_parse_json, "__parseJson", 1));
    JS_SetPropertyStr(ctx, global, "__stringifyJson", JS_NewCFunction(ctx, js_stringify_json, "__stringifyJson", 1));
    JS_SetPropertyStr(ctx, global, "__jsonNew", JS_NewCFunction(ctx, js_json_new, "__jsonNew", 1));
    JS_SetPropertyStr(ctx, global, "__jsonPush", JS_NewCFunction(ctx, js_json_push, "__jsonPush", 4));
    JS_SetPropertyStr(ctx, global, "__jsonDone", JS_NewCFunction(ctx, js_json_done, "__jsonDone", 1));
    JS_SetPropertyStr(ctx, global, "__jsonEnd", JS_NewCFunction(ctx, js_json_end, "__jsonEnd", 1));
    JS_FreeValue(ctx, global);
}
