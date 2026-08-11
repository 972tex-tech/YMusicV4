import AVFoundation
import MediaPlayer
import SwiftUI
import WebKit

// Interactive lab for testing WKWebView background audio on a real device.
// Pick an engine, toggle workarounds, Start, then background/lock the phone by hand.
// Every background episode gets a verdict row; the full log is shareable as CSV.

// MARK: - fixtures (written to Documents at first launch; the page loads them from there)

enum Fixtures {
    static var dir: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    static func install() {
        writeWav(name: "track1.wav", freq: 440, secs: 20)
        writeWav(name: "track2.wav", freq: 660, secs: 300)
        writeWav(name: "silence.wav", freq: 440, secs: 2, amp: 0)
        let html = dir.appendingPathComponent("index.html")
        try? Self.html.data(using: .utf8)!.write(to: html)
    }

    private static func writeWav(name: String, freq: Double, secs: Double, amp: Double = 0.2, rate: Int = 22050) {
        let url = dir.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: url.path) { return }
        let n = Int(secs * Double(rate))
        var pcm = Data(capacity: n * 2)
        for i in 0..<n {
            var s = Int16(amp * 32767 * sin(2 * .pi * freq * Double(i) / Double(rate)))
            withUnsafeBytes(of: &s) { pcm.append(contentsOf: $0) }
        }
        var d = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        d.append("RIFF".data(using: .ascii)!); u32(UInt32(36 + pcm.count))
        d.append("WAVEfmt ".data(using: .ascii)!); u32(16); u16(1); u16(1)
        u32(UInt32(rate)); u32(UInt32(rate * 2)); u16(2); u16(16)
        d.append("data".data(using: .ascii)!); u32(UInt32(pcm.count))
        d.append(pcm)
        try? d.write(to: url)
    }

    static let html = """
    <!doctype html><html><head><meta charset="utf-8"><title>BG Audio Lab</title></head>
    <body style="background:#111;color:#eee;font-family:-apple-system;font-size:26px">
    <p id="s">idle</p><audio id="a" playsinline></audio>
    <script>
    const a = document.getElementById('a'), s = document.getElementById('s');
    function post(d){ try{ webkit.messageHandlers.log.postMessage(d) }catch(e){} }
    ['play','playing','pause','ended','error','stalled','waiting'].forEach(ev =>
      a.addEventListener(ev, () => {
        const m = ev + ' t=' + a.currentTime.toFixed(1) + ' src=' + (a.currentSrc.split('/').pop() || '?');
        s.textContent = m; post({'k':'ev','v':m});
      }));
    document.addEventListener('visibilitychange', () => post({'k':'ev','v':'visibility=' + document.visibilityState}));
    let track = 0;
    a.addEventListener('ended', () => {
      if (track === 1) {
        track = 2; a.src = 'track2.wav';
        a.play().then(() => post({'k':'ev','v':'track2 play ok'}))
                .catch(e => post({'k':'ev','v':'track2 play rejected: ' + e}));
      }
    });
    function start(){
      track = 1; a.src = 'track1.wav';
      a.play().then(() => post({'k':'ev','v':'play() resolved'}))
              .catch(e => post({'k':'ev','v':'play() rejected: ' + e}));
    }
    function kick(){
      a.play().then(() => post({'k':'ev','v':'kick play ok'}))
              .catch(e => post({'k':'ev','v':'kick rejected: ' + e}));
    }
    setInterval(() => post({'k':'tick','v':'t=' + a.currentTime.toFixed(1) + ' paused=' + a.paused}), 1000);
    post({'k':'ev','v':'js loaded'});
    </script></body></html>
    """

    // the prototype-proven fix: YouTube pauses on visibilitychange, but its handler reads
    // document.hidden/visibilityState — spoof those and the pause never triggers
    static let visibilitySpoof = """
    Object.defineProperty(document, 'hidden', { value: false, configurable: true, writable: false });
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true, writable: false });
    """

    // ticks for a real youtube page, where we don't own the DOM
    static let youtubeTicker = """
    if (location.hostname.indexOf('youtube') >= 0) {
      setInterval(() => {
        const v = document.querySelector('video');
        if (v) try {
          webkit.messageHandlers.log.postMessage({'k':'tick','v':'t=' + v.currentTime.toFixed(1) + ' paused=' + v.paused});
        } catch (e) {}
      }, 1000);
    }
    """
}

// MARK: - log

final class Log {
    static let shared = Log()
    let url: URL
    private let handle: FileHandle
    private let t0 = Date()
    private let q = DispatchQueue(label: "lab.log")
    var appState = "active"
    private init() {
        let f = DateFormatter()
        f.dateFormat = "MMdd-HHmmss"
        url = Fixtures.dir.appendingPathComponent("lab-log-\(f.string(from: Date())).csv")
        FileManager.default.createFile(atPath: url.path, contents: Data())
        handle = try! FileHandle(forWritingTo: url)
    }
    func line(_ src: String, _ detail: String) {
        let ms = Int(Date().timeIntervalSince(t0) * 1000)
        let data = "\(ms),\(src),\(appState),\(detail)\n".data(using: .utf8)!
        q.sync { handle.write(data) }
    }
}

// MARK: - harness

final class Harness: NSObject, ObservableObject, WKScriptMessageHandler, AVAudioPlayerDelegate {
    enum Engine: String, CaseIterable, Identifiable {
        case webView = "WebView"
        case youtube = "YouTube"
        case native = "Native"
        var id: String { rawValue }
    }

    struct Episode: Identifiable {
        let id = UUID()
        let label: String
        let detail: String
        let pass: Bool?
    }

    @Published var engine: Engine = .webView
    @Published var useSession = true
    @Published var useNowPlaying = false
    @Published var useSilence = false
    @Published var useKick = false
    @Published var usePrivateAPI = false
    @Published var useVisibilitySpoof = true

    @Published var running = false
    @Published var status = "idle"
    @Published var appState = "active"
    @Published var mediaTime = 0.0
    @Published var paused = true
    @Published var episodes: [Episode] = []
    @Published var delayedArmed = false

    private(set) var webView: WKWebView?
    private var nativePlayer: AVAudioPlayer?
    private var silentPlayer: AVAudioPlayer?

    // media-clock accounting: cumulative forward progress, robust across track changes
    private var lastTickMedia: Double?
    private var lastTickWall: Date?
    private var cumulative = 0.0
    private var bgStart: (wall: Date, cumulative: Double)?
    private var bgMaxGapMs = 0
    private var bgPauseEvents = 0

    override init() {
        super.init()
        Fixtures.install()
        let nc = NotificationCenter.default
        nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.onBackground()
        }
        nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.onForeground()
        }
        nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { n in
            let type = (n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).map(String.init) ?? "?"
            Log.shared.line("session", "interruption type=\(type)")
        }
        applyLaunchArgs()
    }

    // scripted runs: -engine webview|youtube|native, -autostart
    private func applyLaunchArgs() {
        let a = CommandLine.arguments
        if let i = a.firstIndex(of: "-engine"), i + 1 < a.count {
            switch a[i + 1].lowercased() {
            case "youtube": engine = .youtube
            case "native": engine = .native
            default: engine = .webView
            }
        }
        if a.contains("-autostart") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in self?.start() }
        }
    }

    var configSummary: String {
        var parts = [engine.rawValue]
        if useSession { parts.append("session") }
        if useNowPlaying { parts.append("nowplaying") }
        if useSilence { parts.append("silence") }
        if useKick { parts.append("kick") }
        if usePrivateAPI { parts.append("privateAPI") }
        if useVisibilitySpoof { parts.append("visSpoof") }
        return parts.joined(separator: "+")
    }

    // MARK: controls

    func start() {
        stop()
        Log.shared.line("cfg", "start \(configSummary)")
        episodes.insert(Episode(label: "▶︎ \(configSummary)", detail: "test started", pass: nil), at: 0)

        if useSession {
            do {
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
                try AVAudioSession.sharedInstance().setActive(true)
                Log.shared.line("cfg", "audio session .playback active")
            } catch {
                Log.shared.line("cfg", "audio session error: \(error)")
            }
        }
        if useSilence {
            let p = try! AVAudioPlayer(contentsOf: Fixtures.dir.appendingPathComponent("silence.wav"))
            p.numberOfLoops = -1
            p.play()
            silentPlayer = p
            Log.shared.line("cfg", "silent keep-alive running")
        }
        if useNowPlaying { setUpNowPlaying() }

        switch engine {
        case .native:
            startNativeTrack(1)
        case .webView:
            let wv = makeWebView()
            let html = Fixtures.dir.appendingPathComponent("index.html")
            wv.loadFileURL(html, allowingReadAccessTo: Fixtures.dir)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                self?.webView?.evaluateJavaScript("start()", completionHandler: nil)
                Log.shared.line("native", "issued start()")
            }
        case .youtube:
            let wv = makeWebView()
            wv.load(URLRequest(url: URL(string: "https://www.youtube.com/watch?v=LKp-IdxVf4A")!))
            status = "tap play in the page, then background"
        }
        running = true
    }

    func stop() {
        webView?.removeFromSuperview()
        webView?.configuration.userContentController.removeAllScriptMessageHandlers()
        webView = nil
        nativePlayer?.stop()
        nativePlayer = nil
        silentPlayer?.stop()
        silentPlayer = nil
        running = false
        delayedArmed = false
        lastTickMedia = nil
        lastTickWall = nil
        cumulative = 0
        bgStart = nil
        status = "idle"
        mediaTime = 0
        paused = true
        objectWillChange.send()
    }

    // arm, then background/lock immediately: does playback START while backgrounded?
    func armDelayedStart() {
        stop()
        delayedArmed = true
        status = "armed — background/lock NOW; playback starts in 8 s"
        Log.shared.line("cfg", "delayed start armed \(configSummary)")
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in
            guard let self, self.delayedArmed else { return }
            self.delayedArmed = false
            Log.shared.line("cfg", "delayed start firing (state=\(Log.shared.appState))")
            self.episodes.insert(
                Episode(label: "delayed start fired", detail: "app state: \(Log.shared.appState)", pass: nil),
                at: 0
            )
            self.start()
        }
    }

    func kickPlayback(reason: String) {
        if useSession {
            try? AVAudioSession.sharedInstance().setActive(true)
        }
        nativePlayer?.play()
        let js = engine == .youtube
            ? "var v=document.querySelector('video'); if(v) v.play();"
            : "kick()"
        webView?.evaluateJavaScript(js, completionHandler: nil)
        Log.shared.line("kick", "kick (\(reason))")
    }

    // MARK: engines

    private func makeWebView() -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        if usePrivateAPI { applyPrivateForegroundPriority(cfg) }
        cfg.userContentController.add(self, name: "log")
        if useVisibilitySpoof {
            cfg.userContentController.addUserScript(
                WKUserScript(source: Fixtures.visibilitySpoof, injectionTime: .atDocumentStart, forMainFrameOnly: false)
            )
            Log.shared.line("cfg", "visibility spoof injected")
        }
        cfg.userContentController.addUserScript(
            WKUserScript(source: Fixtures.youtubeTicker, injectionTime: .atDocumentEnd, forMainFrameOnly: true)
        )
        let wv = WKWebView(frame: .zero, configuration: cfg)
        if #available(iOS 16.4, *) { wv.isInspectable = true }
        webView = wv
        return wv
    }

    private func applyPrivateForegroundPriority(_ cfg: WKWebViewConfiguration) {
        let sel = NSSelectorFromString("_setAlwaysRunsAtForegroundPriority:")
        guard cfg.responds(to: sel), let m = class_getInstanceMethod(WKWebViewConfiguration.self, sel) else {
            Log.shared.line("cfg", "private API UNAVAILABLE")
            return
        }
        typealias SetBool = @convention(c) (AnyObject, Selector, ObjCBool) -> Void
        unsafeBitCast(method_getImplementation(m), to: SetBool.self)(cfg, sel, true)
        Log.shared.line("cfg", "private _alwaysRunsAtForegroundPriority=true")
    }

    private func startNativeTrack(_ n: Int) {
        let p = try! AVAudioPlayer(contentsOf: Fixtures.dir.appendingPathComponent("track\(n).wav"))
        p.delegate = self
        p.play()
        nativePlayer = p
        Log.shared.line("native", "AVAudioPlayer track\(n) play")
        nativeTicker()
    }

    private func nativeTicker() {
        guard let p = nativePlayer else { return }
        onTick(t: p.currentTime, paused: !p.isPlaying)
        Log.shared.line("js-tick", "t=\(String(format: "%.1f", p.currentTime)) paused=\(!p.isPlaying)")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            if self?.nativePlayer != nil { self?.nativeTicker() }
        }
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        guard player === nativePlayer else { return }
        Log.shared.line("native", "track finished ok=\(flag)")
        startNativeTrack(2)
    }

    private func setUpNowPlaying() {
        UIApplication.shared.beginReceivingRemoteControlEvents()
        let cc = MPRemoteCommandCenter.shared()
        cc.playCommand.addTarget { [weak self] _ in
            Log.shared.line("remote", "playCommand")
            self?.kickPlayback(reason: "remote")
            return .success
        }
        cc.pauseCommand.addTarget { _ in
            Log.shared.line("remote", "pauseCommand")
            return .success
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: "BG Audio Lab",
            MPNowPlayingInfoPropertyPlaybackRate: 1.0,
        ]
        Log.shared.line("cfg", "now-playing registered")
    }

    // MARK: episode accounting

    private func onTick(t: Double, paused isPaused: Bool) {
        let now = Date()
        if let last = lastTickMedia {
            let d = t - last
            if d > 0, d < 5 { cumulative += d }
        }
        if bgStart != nil, let lastWall = lastTickWall {
            bgMaxGapMs = max(bgMaxGapMs, Int(now.timeIntervalSince(lastWall) * 1000))
        }
        lastTickMedia = t
        lastTickWall = now
        mediaTime = t
        paused = isPaused
        if running { status = "playing t=\(String(format: "%.1f", t)) paused=\(isPaused)" }
    }

    private func onBackground() {
        Log.shared.appState = "background"
        appState = "background"
        Log.shared.line("lifecycle", "didEnterBackground")
        guard running else { return }
        bgStart = (Date(), cumulative)
        bgMaxGapMs = 0
        bgPauseEvents = 0
        if useKick {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { [weak self] in
                self?.kickPlayback(reason: "didEnterBackground")
            }
        }
    }

    private func onForeground() {
        Log.shared.appState = "active"
        appState = "active"
        Log.shared.line("lifecycle", "willEnterForeground")
        guard let start = bgStart else { return }
        bgStart = nil
        let wall = Date().timeIntervalSince(start.wall)
        guard wall > 3 else { return }
        let adv = cumulative - start.cumulative
        let pass: Bool
        let text: String
        if adv >= wall * 0.7 {
            pass = true
            text = String(format: "audio kept playing %.0fs of %.0fs in background", adv, wall)
        } else if adv < 1 {
            pass = false
            let cause = bgMaxGapMs > 4000 ? "page/app frozen (max tick gap \(bgMaxGapMs) ms)"
                : bgPauseEvents > 0 ? "paused by system" : "no media progress"
            text = String(format: "stopped — %@ over %.0fs", cause, wall)
        } else {
            pass = false
            text = String(format: "partial — %.0fs of %.0fs (gap %d ms)", adv, wall, bgMaxGapMs)
        }
        let ep = Episode(label: "\(pass ? "✅" : "❌") \(configSummary)", detail: text, pass: pass)
        episodes.insert(ep, at: 0)
        Log.shared.line("verdict", "\(pass ? "PASS" : "FAIL") \(text)")
    }

    // MARK: js messages

    func userContentController(_ u: WKUserContentController, didReceive m: WKScriptMessage) {
        guard let d = m.body as? [String: Any],
              let k = d["k"] as? String, let v = d["v"] as? String else { return }
        Log.shared.line(k == "tick" ? "js-tick" : "js-ev", v)
        if k == "tick" {
            let t = v.split(separator: " ").first.flatMap { Double($0.dropFirst(2)) } ?? 0
            onTick(t: t, paused: v.hasSuffix("paused=true"))
        } else if v.hasPrefix("pause"), bgStart != nil {
            bgPauseEvents += 1
        }
    }
}

// MARK: - UI

struct WebViewContainer: UIViewRepresentable {
    @ObservedObject var harness: Harness
    func makeUIView(context: Context) -> UIView { UIView() }
    func updateUIView(_ view: UIView, context: Context) {
        guard let wv = harness.webView else {
            view.subviews.forEach { $0.removeFromSuperview() }
            return
        }
        if wv.superview !== view {
            view.subviews.forEach { $0.removeFromSuperview() }
            wv.frame = view.bounds
            wv.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(wv)
        }
    }
}

struct ContentView: View {
    @StateObject var harness = Harness()

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                form
                // outside the Form: lazy form rows would detach the webview from the
                // window, and WebKit suspends media in a window-detached webview
                if harness.engine != .native, harness.webView != nil {
                    WebViewContainer(harness: harness)
                        .frame(height: 220)
                }
            }
            .navigationTitle("BG Audio Lab")
        }
        .navigationViewStyle(.stack)
    }

    private var form: some View {
        Form {
                Section("Engine") {
                    Picker("Engine", selection: $harness.engine) {
                        ForEach(Harness.Engine.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
                Section("Workarounds") {
                    Toggle("AVAudioSession .playback", isOn: $harness.useSession)
                    Toggle("Now Playing + remote commands", isOn: $harness.useNowPlaying)
                    Toggle("Silent keep-alive loop", isOn: $harness.useSilence)
                    Toggle("play() kick on background", isOn: $harness.useKick)
                    Toggle("Private FG-priority API", isOn: $harness.usePrivateAPI)
                    Toggle("Spoof document.hidden=false", isOn: $harness.useVisibilitySpoof)
                }
                Section {
                    Button(harness.running ? "Restart test" : "Start test") { harness.start() }
                    Button("Arm delayed start (8 s) — then background NOW") { harness.armDelayedStart() }
                    if harness.running || harness.delayedArmed {
                        Button("Stop", role: .destructive) { harness.stop() }
                    }
                } footer: {
                    Text("Start: play, then background/lock by hand; return to the app to see the verdict. Delayed start: checks whether playback can BEGIN while backgrounded.")
                }
                Section("Status") {
                    Text(harness.status).font(.footnote.monospaced())
                    HStack {
                        Text("config").foregroundColor(.secondary)
                        Spacer()
                        Text(harness.configSummary).font(.footnote)
                    }
                }
                Section("Episodes (newest first)") {
                    if harness.episodes.isEmpty {
                        Text("none yet").foregroundColor(.secondary)
                    }
                    ForEach(harness.episodes) { ep in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ep.label).font(.subheadline.bold())
                                .foregroundColor(ep.pass == nil ? .secondary : (ep.pass! ? .green : .red))
                            Text(ep.detail).font(.footnote).foregroundColor(.secondary)
                        }
                    }
                }
                Section {
                    Button("Share log CSV") { share(Log.shared.url) }
                }
            }
    }

    private func share(_ url: URL) {
        let av = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first?.rootViewController?
            .present(av, animated: true)
    }
}

@main
struct BgAudioLabApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
