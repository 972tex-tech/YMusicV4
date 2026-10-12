import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * `-PuseLocalServerIp`: point the build at a dev YMusic server on the local network,
 * `http://<local.server.ip>:<local.server.port, default 8787>` from local.properties, instead of
 * `remote.config.url`. Off by default; a release is never built with it.
 */
fun Project.useLocalServerIp(): Boolean =
    providers.gradleProperty("useLocalServerIp").orNull?.let { it != "false" } ?: false

/** `local.server.ip` with [useLocalServerIp], else "" — the `LOCAL_IP` constant. */
fun Project.localServerIp(): String {
    if (!useLocalServerIp()) return ""
    return localProp("local.server.ip")?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw GradleException("-PuseLocalServerIp needs local.server.ip in local.properties (e.g. local.server.ip=192.168.1.27)")
}

/** The dev server's url with [useLocalServerIp], else null. */
fun Project.localServerUrl(): String? =
    if (useLocalServerIp()) "http://${localServerIp()}:${localProp("local.server.port")?.trim() ?: "8787"}" else null
