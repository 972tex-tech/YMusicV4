package kmphttp

/** True only when the test process is running on macOS (not iOS/Linux/JVM-on-mac). */
internal expect fun isMacosHost(): Boolean
