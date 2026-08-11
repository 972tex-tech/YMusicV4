import SwiftUI
import WebKit
import Network
import Security

// Can WKWebView fetch https://127.0.0.1 with a self-signed cert we accept? The last unknown for the
// loopback-proxy interception path (embed stays https/secure-context; proxy served over https so no
// mixed content). The crux is whether WKWebView delivers the server-trust challenge for a fetch()
// SUBRESOURCE (long-standing WebKit question) — if it does, we accept the cert and it works. Runs
// headless on the simulator; prints [tls] lines.

@main
struct ProbeApp: App {
    var body: some Scene { WindowGroup { ProbeView() } }
}

struct ProbeView: View {
    @StateObject private var model = ProbeModel()
    var body: some View { WebRep(webView: model.webView).onAppear { model.start() } }
}

struct WebRep: UIViewRepresentable {
    let webView: WKWebView
    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ v: WKWebView, context: Context) {}
}

final class ProbeModel: NSObject, ObservableObject, WKNavigationDelegate {
    let webView: WKWebView
    private let tls = TlsLoopbackServer()
    private var probed = false

    override init() {
        webView = WKWebView(frame: .zero, configuration: WKWebViewConfiguration())
        super.init()
        webView.navigationDelegate = self
    }

    func start() {
        do {
            try tls.start(body: "hello from https loopback")
        } catch {
            print("[tls] server start FAILED: \(error)"); return
        }
        // Wait for the listener to report its port, then load the https embed page and probe.
        waitForPort()
    }

    private func waitForPort(_ tries: Int = 0) {
        if tls.port != 0 {
            print("[tls] https loopback listening on 127.0.0.1:\(tls.port)")
            webView.loadHTMLString("<html><body>probe</body></html>", baseURL: URL(string: "https://www.youtube.com/embed"))
            return
        }
        if tries > 100 { print("[tls] listener never became ready"); return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { self.waitForPort(tries + 1) }
    }

    // The decisive hook: is this called for the fetch() subresource to https://127.0.0.1?
    func webView(_ wv: WKWebView, didReceive challenge: URLAuthenticationChallenge,
                 completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let sp = challenge.protectionSpace
        print("[tls] CHALLENGE host=\(sp.host):\(sp.port) method=\(sp.authenticationMethod)")
        if sp.authenticationMethod == NSURLAuthenticationMethodServerTrust, sp.host == "127.0.0.1",
           let trust = sp.serverTrust {
            print("[tls] -> accepting loopback server trust")
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }

    func webView(_ wv: WKWebView, didFinish nav: WKNavigation!) {
        guard !probed else { return }
        probed = true
        print("[tls] page origin=\(wv.url?.absoluteString ?? "nil") secure=\(wv.url?.scheme ?? "?")")
        let url = "https://127.0.0.1:\(tls.port)/hello"
        print("[tls] probing fetch('\(url)') from the https page")
        let js = "const r = await fetch('\(url)'); return 'body=' + (await r.text());"
        wv.callAsyncJavaScript(js, arguments: [:], in: nil, in: .page) { result in
            switch result {
            case .success(let v): print("[tls] RESULT -> OK: \(v)")
            case .failure(let e):
                let ns = e as NSError
                let msg = ns.userInfo["WKJavaScriptExceptionMessage"] as? String ?? ns.localizedDescription
                print("[tls] RESULT -> ERR: \(msg)  [domain=\(ns.domain) code=\(ns.code)]")
            }
            print("[tls] DONE")
        }
    }
}

/// An https loopback server (Network.framework + a TLS identity from the bundled self-signed p12).
final class TlsLoopbackServer {
    private var listener: NWListener?
    private(set) var port: UInt16 = 0

    func start(body: String) throws {
        let identity = try Self.loadIdentity()
        let tlsOptions = NWProtocolTLS.Options()
        sec_protocol_options_set_local_identity(tlsOptions.securityProtocolOptions, sec_identity_create(identity)!)
        let params = NWParameters(tls: tlsOptions)
        params.allowLocalEndpointReuse = true

        let listener = try NWListener(using: params) // ephemeral port
        self.listener = listener
        let response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: \(body.utf8.count)\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n\(body)"
        listener.newConnectionHandler = { conn in
            conn.start(queue: .global())
            conn.receive(minimumIncompleteLength: 1, maximumLength: 4096) { _, _, _, _ in
                conn.send(content: response.data(using: .utf8), completion: .contentProcessed { _ in conn.cancel() })
            }
        }
        listener.stateUpdateHandler = { state in
            if case .ready = state { self.port = listener.port?.rawValue ?? 0 }
        }
        listener.start(queue: .global())
    }

    private static func loadIdentity() throws -> SecIdentity {
        guard let url = Bundle.main.url(forResource: "identity", withExtension: "p12"),
              let data = try? Data(contentsOf: url) else {
            throw NSError(domain: "tls", code: 1, userInfo: [NSLocalizedDescriptionKey: "identity.p12 missing"])
        }
        var items: CFArray?
        let status = SecPKCS12Import(data as CFData, [kSecImportExportPassphrase as String: "ymusic"] as CFDictionary, &items)
        guard status == errSecSuccess,
              let arr = items as? [[String: Any]],
              let idRef = arr.first?[kSecImportItemIdentity as String] else {
            throw NSError(domain: "tls", code: Int(status), userInfo: [NSLocalizedDescriptionKey: "SecPKCS12Import failed"])
        }
        return idRef as! SecIdentity
    }
}
