package kmpcommon

// Multiply by Long factors so the whole product is computed in Long. `this * 1024 * 1024L` would
// evaluate the first `Int * Int` in Int and overflow before the widening (e.g. 2048.gigabytes went
// negative) — the exact bug these helpers exist to prevent.
val Int.kilobytes get() = this * 1024L
val Long.kilobytes get() = this * 1024L
val Int.megabytes get() = this * 1024L * 1024L
val Long.megabytes get() = this * 1024L * 1024L
val Int.gigabytes get() = this * 1024L * 1024L * 1024L
val Long.gigabytes get() = this * 1024L * 1024L * 1024L
