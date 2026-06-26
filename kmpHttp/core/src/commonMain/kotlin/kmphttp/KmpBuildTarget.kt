package kmphttp

/**
 * Enum representing the runtime platform.
 */
enum class KmpBuildTarget {
    JVM,
    APPLE,
    LINUX,
    JS_BROWSER,
    JS_NODE,
    WASM_JS_BROWSER,
    WASM_JS_NODE;

    /** True if running in a browser environment. */
    val isBrowser: Boolean get() = this == JS_BROWSER || this == WASM_JS_BROWSER

    /** True if running in Node.js environment. */
    val isNode: Boolean get() = this == JS_NODE || this == WASM_JS_NODE

    /** True if running on JavaScript (JS or WasmJS). */
    val isJs: Boolean get() = this == JS_BROWSER || this == JS_NODE
    val isWasmJs: Boolean get() = this == WASM_JS_BROWSER || this == WASM_JS_NODE
}

/**
 * The current platform type at runtime.
 */
internal expect val BUILD_TARGET: KmpBuildTarget
