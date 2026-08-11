/**
 * @fileoverview Closure externs — the ONLY names the ADVANCED pass must not rename. This is the
 * complete outside world of the bundle: the C-registered natives (called as bare identifiers), the
 * transport globals C captures by name right after the bundle evals, and the host-evaled
 * `versions` object. Mirrors src/natives.d.ts (the TypeScript view of the same boundary).
 * Wire JSON keys are NOT listed here — they stay literal because the code accesses them
 * quoted/bracketed only (Closure never renames quoted properties).
 * @externs
 */

/* ---- host transport natives (quickjs_native.c / quickjs_jni.c) ---- */

/**
 * @param {number} id
 * @param {string} command
 * @return {number}
 */
function __hostOpen(id, command) {}
/**
 * @param {number} id
 * @param {!Uint8Array} bytes
 * @param {number} off
 * @param {number} len
 * @return {number}
 */
function __hostData(id, bytes, off, len) {}
/**
 * @param {number} id
 * @param {?string} reason
 */
function __hostClose(id, reason) {}
/** @param {number} id */
function __hostCloseRead(id) {}

/* ---- C pipe buffer (quickjs_pipe.c) ---- */

/**
 * @param {number} id
 * @return {*}
 */
function __pbNew(id) {}
/**
 * @param {*} pb
 * @param {!Uint8Array} buf
 * @param {number} off
 * @param {number} len
 * @return {number|!Promise<number>}
 */
function __pbRecv(pb, buf, off, len) {}
/**
 * @param {*} pb
 * @return {number|!Promise<number>}
 */
function __pbInt(pb) {}
/**
 * @param {*} pb
 * @return {number|!Promise<number>}
 */
function __pbLong(pb) {}
/**
 * @param {*} pb
 * @param {boolean} strict
 * @return {?string|!Promise<?string>}
 */
function __pbLine(pb, strict) {}
/**
 * @param {*} pb
 * @param {number} rootType
 * @return {*}
 */
function __pbJson(pb, rootType) {}
/**
 * @param {*} pb
 * @return {string|!Promise<string>}
 */
function __pbUtf8(pb) {}
/** @param {*} pb */
function __pbCloseRead(pb) {}

/* ---- native UTF-8 (quickjs_pipe.c) ---- */

/**
 * @param {!Uint8Array} bytes
 * @param {number} off
 * @param {number} len
 * @return {string}
 */
function __utf8Decode(bytes, off, len) {}
/**
 * @param {string} str
 * @param {number} from
 * @param {number} to
 * @param {!Uint8Array} buf
 * @param {number} off
 * @return {number}
 */
function __utf8Into(str, from, to, buf, off) {}
/**
 * @param {string} str
 * @param {number} from
 * @param {number} to
 * @return {!Uint8Array}
 */
function __utf8Encode(str, from, to) {}
/**
 * @param {string} str
 * @param {number} from
 * @param {number} to
 * @return {number}
 */
function __utf8Length(str, from, to) {}

/* ---- native streaming JSON encoder (quickjs_json.c) ---- */

/**
 * @param {*} value
 * @return {*}
 */
function __jsonEncNew(value) {}
/**
 * @param {*} enc
 * @param {!Uint8Array} buf
 * @param {number} off
 * @return {number}
 */
function __jsonEncFill(enc, buf, off) {}
/**
 * @param {*} enc
 * @return {boolean}
 */
function __jsonEncDone(enc) {}

/**
 * @param {!Uint8Array} bytes
 * @return {*}
 */
function __parseJson(bytes) {}
/**
 * @param {*} value
 * @return {!Uint8Array}
 */
function __stringifyJson(value) {}
/**
 * @param {number=} rootType
 * @return {*}
 */
function __jsonNew(rootType) {}
/**
 * @param {*} p
 * @param {!Uint8Array} bytes
 * @param {number} off
 * @param {number} len
 * @return {number}
 */
function __jsonPush(p, bytes, off, len) {}
/**
 * @param {*} p
 * @return {*}
 */
function __jsonEnd(p) {}

/* ---- transport globals the bundle DEFINES and C captures by name after the eval ---- */

/** @type {function(number, string): number} */
var __jsOpen;
/** @type {function(number)} */
var __jsClose;
/** @type {function(number)} */
var __jsCloseRead;
/** @type {function(number)} */
var __jsDrain;

/* ---- console/diagnostics bridge (quickjs_native.c / quickjs_jni.c) ---- */

/**
 * @param {number} level
 * @param {string} msg
 * @return {undefined}
 */
function __consoleLog(level, msg) {}

/* The bundle ASSIGNS globalThis.console (console.ts) — externed so the name and its method
 * names survive the ADVANCED pass (the CUSTOM environment ships no browser console extern). */
var console = {};
/** @param {...*} var_args */
console.log = function (var_args) {};
/** @param {...*} var_args */
console.info = function (var_args) {};
/** @param {...*} var_args */
console.warn = function (var_args) {};
/** @param {...*} var_args */
console.error = function (var_args) {};

/* ---- backward-compat versions, installed frozen from C before the bundle runs ---- */

/** @type {{app: number}} */
var versions;
