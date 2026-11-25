import Darwin
import Foundation

enum NetworkUtils {

    /// Returns local IPv4 addresses sorted by LAN priority:
    /// en0 (Wi-Fi) -> bridge100 (iPhone Personal Hotspot) -> en* (Ethernet/USB) -> other private IPv4.
    static func getLocalIPv4Addresses() -> [String] {
        var candidates: [(Int, String)] = []
        var ifaddrPointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddrPointer) == 0, let firstAddr = ifaddrPointer else {
            return []
        }
        defer { freeifaddrs(ifaddrPointer) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = firstAddr
        while let current = ptr {
            let interface = current.pointee
            let flags = Int32(interface.ifa_flags)
            let isUp = (flags & IFF_UP) == IFF_UP
            let isLoopback = (flags & IFF_LOOPBACK) == IFF_LOOPBACK

            if isUp && !isLoopback, let addr = interface.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: interface.ifa_name)
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(
                    addr,
                    socklen_t(addr.pointee.sa_len),
                    &hostname,
                    socklen_t(hostname.count),
                    nil,
                    0,
                    NI_NUMERICHOST
                ) == 0 {
                    let ip = String(cString: hostname)
                    let priority: Int
                    if name == "en0" {
                        priority = 1
                    } else if name.hasPrefix("bridge") {
                        priority = 2
                    } else if name.hasPrefix("en") {
                        priority = 3
                    } else if name.hasPrefix("pdp_ip") || name.hasPrefix("utun") {
                        priority = 10
                    } else {
                        priority = 5
                    }
                    if isPrivateIP(ip) || priority <= 3 {
                        candidates.append((priority, ip))
                    }
                }
            }
            ptr = interface.ifa_next
        }

        var seen = Set<String>()
        return candidates
            .sorted { $0.0 < $1.0 }
            .map(\.1)
            .filter { seen.insert($0).inserted }
    }

    private static func isPrivateIP(_ ip: String) -> Bool {
        ip.hasPrefix("192.168.") ||
        ip.hasPrefix("10.") ||
        ip.hasPrefix("172.16.") || ip.hasPrefix("172.17.") || ip.hasPrefix("172.18.") ||
        ip.hasPrefix("172.19.") || ip.hasPrefix("172.2") || ip.hasPrefix("172.30.") ||
        ip.hasPrefix("172.31.")
    }
}
