import SwiftUI
import WebKit

// Serve media to the WebView over the JS↔native MESSAGE CHANNEL (no http/https loopback). Framed, async,
// chunked, pull-based: the fetch override builds `new Response(readableStream)`; the stream's pull() does
// `await webkit.messageHandlers.media.postMessage({op:'read'})` (WKScriptMessageHandlerWithReply returns a
// Promise), native replies with the next BINARY chunk, JS enqueues it. Decisive questions:
//   (1) does a with-reply handler return BINARY (Data → ArrayBuffer) to JS — no base64?
//   (2) does the pull-driven ReadableStream → Response round-trip the whole payload?
// Runs headless on the simulator; prints [msg] lines.

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

final class ProbeModel: NSObject, ObservableObject, WKScriptMessageHandlerWithReply, WKNavigationDelegate {
    let webView: WKWebView
    private var started = false
    private var streams: [String: [Data]] = [:] // requestId → remaining chunks
    private var nextId = 0

    override init() {
        let cfg = WKWebViewConfiguration()
        webView = WKWebView(frame: .zero, configuration: cfg)
        super.init()
        cfg.userContentController.addScriptMessageHandler(self, contentWorld: .page, name: "media")
        cfg.userContentController.add(ResultHandler(), name: "result")
        webView.navigationDelegate = self
    }

    func start() {
        // https origin — secure context intact, and no http anywhere so no mixed content at all.
        webView.loadHTMLString("<html><body>probe</body></html>", baseURL: URL(string: "https://www.youtube.com/embed"))
    }

    func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage,
                              replyHandler: @escaping (Any?, String?) -> Void) {
        let body = message.body as? [String: Any] ?? [:]
        // Chunks cross as base64 strings — WKScriptMessageHandlerWithReply won't serialize Data/ArrayBuffer.
        switch body["op"] as? String {
        case "open":
            nextId += 1
            let id = "r\(nextId)"
            streams[id] = ["hello ", "from native ", "reply"].map { Data($0.utf8) } // a 3-chunk "media" response
            replyHandler(["requestId": id, "status": 200], nil)
        case "read":
            let id = body["requestId"] as? String ?? ""
            if var chunks = streams[id], !chunks.isEmpty {
                let next = chunks.removeFirst()
                streams[id] = chunks
                replyHandler(next.base64EncodedString(), nil) // next chunk, base64
            } else {
                streams[id] = nil
                replyHandler("", nil) // empty string = end-of-stream
            }
        default:
            replyHandler(NSNull(), nil)
        }
    }

    func webView(_ wv: WKWebView, didFinish nav: WKNavigation!) {
        guard !started else { return }
        started = true
        let js = """
        (async function () {
          function post(s){ window.webkit.messageHandlers.result.postMessage(String(s)); }
          function b64ToU8(b64){ const bin = atob(b64); const u8 = new Uint8Array(bin.length); for (let i=0;i<bin.length;i++) u8[i]=bin.charCodeAt(i); return u8; }
          const H = window.webkit.messageHandlers.media;
          post('postMessage-returns=' + Object.prototype.toString.call(H.postMessage({op:'noop'})));

          // Pull-driven stream → Response, exactly what the fetch override would return to the player.
          try {
            const meta = await H.postMessage({op:'open', url:'https://x.googlevideo.com/videoplayback?sabr=1', method:'POST'});
            post('open status=' + meta.status + ' id=' + meta.requestId);
            const id = meta.requestId;
            let frames = 0;
            const stream = new ReadableStream({
              async pull(controller){
                const b64 = await H.postMessage({op:'read', requestId:id});
                if (b64 === '') { controller.close(); }
                else { frames++; controller.enqueue(b64ToU8(b64)); }
              }
            });
            const resp = new Response(stream, {status: meta.status, headers: {'Content-Type':'application/vnd.yt-ump'}});
            const text = await resp.text();
            post('STREAMED frames=' + frames + ' text=' + JSON.stringify(text));
          } catch (e) { post('stream-ERR:' + e); }
          post('DONE');
        })();
        """
        wv.evaluateJavaScript(js, completionHandler: nil)
    }
}

final class ResultHandler: NSObject, WKScriptMessageHandler {
    func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage) {
        print("[msg] \(message.body)")
    }
}
