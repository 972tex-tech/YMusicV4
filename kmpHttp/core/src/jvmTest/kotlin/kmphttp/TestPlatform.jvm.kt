package kmphttp

// The JVM engine (OkHttp) loads client certs via KeyStore, not the macOS keychain.
internal actual fun isMacosHost(): Boolean = false
