import Combine
import Foundation
import Network

/// Discovers both Android (NSD) and iOS (Bonjour) LivePhoto Link receivers on LAN.
final class BonjourBrowser: NSObject, ObservableObject, NetServiceBrowserDelegate, NetServiceDelegate {

    @Published var discoveredDevices: [DiscoveredDevice] = []

    private let browser = NetServiceBrowser()
    private var resolvingServices: [NetService] = []

    override init() {
        super.init()
        browser.delegate = self
    }

    func start() {
        stop()
        DispatchQueue.main.async {
            self.discoveredDevices = []
            self.browser.searchForServices(ofType: "\(ProtocolConstants.bonjourServiceType).", inDomain: "local.")
        }
    }

    func stop() {
        DispatchQueue.main.async {
            self.browser.stop()
            self.resolvingServices.forEach { $0.stop() }
            self.resolvingServices.removeAll()
        }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        service.delegate = self
        resolvingServices.append(service)
        service.resolve(withTimeout: 5.0)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        DispatchQueue.main.async {
            self.discoveredDevices.removeAll { $0.serviceName == service.name }
        }
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let addresses = sender.addresses, sender.port > 0 else { return }
        var resolvedIP: String?
        for addrData in addresses {
            addrData.withUnsafeBytes { ptr in
                guard let sa = ptr.bindMemory(to: sockaddr.self).baseAddress,
                      sa.pointee.sa_family == UInt8(AF_INET) else { return }
                var hostBuf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(
                    sa,
                    socklen_t(sa.pointee.sa_len),
                    &hostBuf,
                    socklen_t(hostBuf.count),
                    nil,
                    0,
                    NI_NUMERICHOST
                ) == 0 {
                    resolvedIP = String(cString: hostBuf)
                }
            }
            if resolvedIP != nil { break }
        }
        guard let ip = resolvedIP else { return }

        var deviceName = sender.name
        var deviceId = sender.name
        if let txtData = sender.txtRecordData() {
            let dict = NetService.dictionary(fromTXTRecord: txtData)
            if let nameData = dict["name"], let str = String(data: nameData, encoding: .utf8), !str.isEmpty {
                deviceName = str
            }
            if let idData = dict["id"], let str = String(data: idData, encoding: .utf8), !str.isEmpty {
                deviceId = str
            }
        }

        let item = DiscoveredDevice(
            serviceName: sender.name,
            deviceId: deviceId,
            deviceName: deviceName,
            host: ip,
            port: sender.port
        )
        DispatchQueue.main.async {
            self.discoveredDevices.removeAll { $0.deviceId == item.deviceId || $0.host == item.host }
            self.discoveredDevices.append(item)
        }
    }
}
