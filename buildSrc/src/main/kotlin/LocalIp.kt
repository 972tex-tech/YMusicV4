import org.gradle.api.Project
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * This machine's IPv4 address on the local network — where a phone or emulator on the same network
 * reaches a dev server running here. Prefers 192.168.x.x (Wi-Fi/LAN), then any other private
 * address; 127.0.0.1 when there is none. Container bridges, VPN tunnels and loopback never count.
 */
fun localIPv4(): String {
    val skipped = listOf("docker", "br-", "veth", "bridge", "vboxnet", "vmnet", "utun", "tun", "tap")
    val addresses = NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
        .filter { iface -> skipped.none { iface.name.startsWith(it) } }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
        .toList()
    return addresses.firstOrNull { it.startsWith("192.168.") } ?: addresses.firstOrNull() ?: "127.0.0.1"
}

/**
 * `-PuseLocalServerIp`: point the build at the YMusic server running on this machine,
 * `http://<localIPv4>:<-PlocalServerPort, default 8787>`, instead of `remote.config.url`. Off by
 * default; a release is never built with it.
 */
fun Project.useLocalServerIp(): Boolean =
    providers.gradleProperty("useLocalServerIp").orNull?.let { it != "false" } ?: false

/** The build machine's LAN address with [useLocalServerIp], else "" — the `LOCAL_IP` constant. */
fun Project.localServerIp(): String = if (useLocalServerIp()) localIPv4() else ""

/** The dev server's url with [useLocalServerIp], else null. */
fun Project.localServerUrl(): String? =
    if (useLocalServerIp()) "http://${localIPv4()}:${providers.gradleProperty("localServerPort").orNull ?: "8787"}" else null
