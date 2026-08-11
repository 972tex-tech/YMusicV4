plugins {
    // KGP forbids the full java plugin on the KMP :desktopApp module, and io.sentry.jvm.gradle
    // registers its source-context tasks only against it — so this otherwise-empty side module
    // hosts the plugin. :desktopApp mirrors the generated sentry-debug-meta.properties into its
    // jvmMain resources and hangs the upload off its jar task.
    `java`
    id("io.sentry.jvm.gradle")
}

sentry {
    org.set("na-6d")
    projectName.set("ymusic-kmp")
    authToken.set(sentryAuthToken())
    includeSourceContext.set(true)
    // The java main sourceSet here is empty by design; the real sources ride in explicitly.
    additionalSourceDirsForSourceContext.set(
        setOf(
            "../src/jvmMain/kotlin",
            "../../commonUi/src/commonMain/kotlin",
            "../../commonUi/src/jvmMain/kotlin",
        ),
    )
    // :desktopApp already carries the KMP wrapper's pinned sentry-java; nothing to install here.
    autoInstallation { enabled.set(false) }
    telemetry.set(false)
}
