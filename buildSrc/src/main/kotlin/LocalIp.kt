import java.net.Inet4Address
import java.net.NetworkInterface

/** Stands for [localIPv4] in a dev service url: `remote.config.url=http://{localIp}:8787`. */
const val LOCAL_IP_PLACEHOLDER = "{localIp}"

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

/** [url] with [LOCAL_IP_PLACEHOLDER] resolved; looks up the network only when it is there. */
fun withLocalIp(url: String): String =
    if (LOCAL_IP_PLACEHOLDER in url) url.replace(LOCAL_IP_PLACEHOLDER, localIPv4()) else url
