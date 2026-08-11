import AVFoundation
import MediaPlayer
import UIKit
import WebKit

// Minimal single-file spike app: measures whether WKWebView <audio> keeps playing when the
// app is backgrounded, under a matrix of known workarounds selected by "-mode <name>".
//
// Modes (cumulative on top of `session` unless noted):
//   plain      - UIBackgroundModes:audio in plist only; AVAudioSession untouched
//   session    - AVAudioSession .playback + setActive(true)
//   nowplaying - session + MPNowPlayingInfoCenter + MPRemoteCommandCenter targets
//   silence    - session + a native silent AVAudioPlayer loop (keep-alive hack)
//   kick       - session + on didEnterBackground: re-setActive + JS audio.play()
//   private    - session + WKWebViewConfiguration _alwaysRunsAtForegroundPriority (private API)
//   all        - session + nowplaying + silence + kick + private
//   native     - control group: AVAudioPlayer instead of the WebView

func argValue(_ name: String) -> String? {
    let a = CommandLine.arguments
    guard let i = a.firstIndex(of: name), i + 1 < a.count else { return nil }
    return a[i + 1]
}

let modeName = argValue("-mode") ?? "session"

struct Flags {
    var session = false
    var nowplaying = false
    var silence = false
    var kick = false
    var privateAPI = false
    var nativePlayer = false
}

func makeFlags(_ m: String) -> Flags {
    var f = Flags()
    if m == "plain" { return f }
    f.session = true
    switch m {
    case "session": break
    case "nowplaying": f.nowplaying = true
    case "silence": f.silence = true
    case "kick": f.kick = true
    case "private": f.privateAPI = true
    case "all":
        f.nowplaying = true
        f.silence = true
        f.kick = true
        f.privateAPI = true
    case "native": f.nativePlayer = true
    default: break
    }
    return f
}

let FLAGS = makeFlags(modeName)

final class Log {
    static let shared = Log()
    private let handle: FileHandle
    private let t0 = Date()
    private let q = DispatchQueue(label: "spike.log")
    var appState = "launch"
    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let url = docs.appendingPathComponent("spike-log.csv")
        FileManager.default.createFile(atPath: url.path, contents: Data())
        handle = try! FileHandle(forWritingTo: url)
    }
    func line(_ src: String, _ detail: String) {
        let ms = Int(Date().timeIntervalSince(t0) * 1000)
        let state = appState
        let data = "\(ms),\(src),\(state),\(detail)\n".data(using: .utf8)!
        q.sync { handle.write(data) }
    }
}

final class SpikeController: NSObject, WKScriptMessageHandler, AVAudioPlayerDelegate {
    var webView: WKWebView?
    var silentPlayer: AVAudioPlayer?
    var nativePlayer: AVAudioPlayer?
    var ticker: Timer?

    func start(in view: UIView) {
        if FLAGS.session {
            do {
                try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
                try AVAudioSession.sharedInstance().setActive(true)
                Log.shared.line("cfg", "audio session .playback active")
            } catch {
                Log.shared.line("cfg", "audio session error: \(error)")
            }
        } else {
            Log.shared.line("cfg", "audio session untouched")
        }

        if FLAGS.silence {
            let url = Bundle.main.url(forResource: "silence", withExtension: "wav")!
            let p = try! AVAudioPlayer(contentsOf: url)
            p.numberOfLoops = -1
            p.play()
            silentPlayer = p
            Log.shared.line("cfg", "silent keep-alive player running")
        }

        if FLAGS.nowplaying { setUpNowPlaying() }

        if FLAGS.nativePlayer {
            startNativeTrack(1)
        } else {
            setUpWebView(in: view)
        }

        ticker = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            var extra = ""
            if let p = self?.nativePlayer {
                extra = " nativeT=\(String(format: "%.1f", p.currentTime)) playing=\(p.isPlaying)"
            }
            Log.shared.line("native", "tick\(extra)")
        }

        let nc = NotificationCenter.default
        nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { n in
            let type = (n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).map(String.init) ?? "?"
            Log.shared.line("session", "interruption type=\(type)")
        }
        nc.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { _ in
            Log.shared.line("session", "media services reset")
        }
    }

    func setUpWebView(in view: UIView) {
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        if FLAGS.privateAPI { applyPrivateForegroundPriority(cfg) }
        cfg.userContentController.add(self, name: "log")
        let wv = WKWebView(
            frame: CGRect(x: 0, y: 120, width: view.bounds.width, height: 300),
            configuration: cfg
        )
        view.addSubview(wv)
        webView = wv
        let html = Bundle.main.url(forResource: "index", withExtension: "html")!
        wv.loadFileURL(html, allowingReadAccessTo: html.deletingLastPathComponent())
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            Log.shared.line("native", "issuing start()")
            wv.evaluateJavaScript("start()") { _, e in
                if let e { Log.shared.line("native", "start() error: \(e)") }
            }
        }
    }

    func applyPrivateForegroundPriority(_ cfg: WKWebViewConfiguration) {
        let sel = NSSelectorFromString("_setAlwaysRunsAtForegroundPriority:")
        guard cfg.responds(to: sel),
              let m = class_getInstanceMethod(WKWebViewConfiguration.self, sel)
        else {
            Log.shared.line("cfg", "private _alwaysRunsAtForegroundPriority UNAVAILABLE")
            return
        }
        typealias SetBool = @convention(c) (AnyObject, Selector, ObjCBool) -> Void
        unsafeBitCast(method_getImplementation(m), to: SetBool.self)(cfg, sel, true)
        Log.shared.line("cfg", "private _alwaysRunsAtForegroundPriority=true")
    }

    func setUpNowPlaying() {
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
            MPMediaItemPropertyTitle: "BG Audio Spike",
            MPMediaItemPropertyPlaybackDuration: 140.0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: 0.0,
            MPNowPlayingInfoPropertyPlaybackRate: 1.0,
        ]
        Log.shared.line("cfg", "now-playing + remote commands registered")
    }

    func kickPlayback(reason: String) {
        if FLAGS.session {
            do {
                try AVAudioSession.sharedInstance().setActive(true)
                Log.shared.line("kick", "session re-activated (\(reason))")
            } catch {
                Log.shared.line("kick", "session re-activate failed: \(error)")
            }
        }
        if let p = nativePlayer {
            p.play()
            Log.shared.line("kick", "native play() (\(reason))")
        }
        webView?.evaluateJavaScript("kick()") { _, e in
            Log.shared.line("kick", e == nil ? "kick() issued (\(reason))" : "kick() error: \(e!)")
        }
    }

    func startNativeTrack(_ n: Int) {
        let url = Bundle.main.url(forResource: "track\(n)", withExtension: "wav")!
        let p = try! AVAudioPlayer(contentsOf: url)
        p.delegate = self
        p.play()
        nativePlayer = p
        Log.shared.line("native", "AVAudioPlayer track\(n) play")
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        guard player === nativePlayer else { return }
        Log.shared.line("native", "track finished ok=\(flag)")
        startNativeTrack(2)
    }

    func userContentController(_ u: WKUserContentController, didReceive m: WKScriptMessage) {
        guard let d = m.body as? [String: Any] else { return }
        let k = d["k"] as? String ?? "?"
        let v = d["v"] as? String ?? "?"
        Log.shared.line(k == "tick" ? "js-tick" : "js-ev", v)
    }
}

final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    let controller = SpikeController()

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        Log.shared.appState = "active"
        Log.shared.line("lifecycle", "didFinishLaunching mode=\(modeName)")
        let w = UIWindow(frame: UIScreen.main.bounds)
        let vc = UIViewController()
        vc.view.backgroundColor = .black
        let label = UILabel(frame: CGRect(x: 0, y: 60, width: w.bounds.width, height: 40))
        label.text = "bg-audio spike — mode: \(modeName)"
        label.textColor = .white
        label.textAlignment = .center
        vc.view.addSubview(label)
        w.rootViewController = vc
        w.makeKeyAndVisible()
        window = w
        controller.start(in: vc.view)

        let nc = NotificationCenter.default
        nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            Log.shared.appState = "background"
            Log.shared.line("lifecycle", "didEnterBackground")
            if FLAGS.kick {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
                    self?.controller.kickPlayback(reason: "didEnterBackground")
                }
            }
        }
        nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { _ in
            Log.shared.appState = "foreground(will)"
            Log.shared.line("lifecycle", "willEnterForeground")
        }
        nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            Log.shared.appState = "active"
            Log.shared.line("lifecycle", "didBecomeActive")
        }
        nc.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { _ in
            Log.shared.appState = "inactive"
            Log.shared.line("lifecycle", "willResignActive")
        }
        return true
    }

    func application(
        _ app: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        Log.shared.line("lifecycle", "openURL \(url)")
        return true
    }
}

_ = UIApplicationMain(CommandLine.argc, CommandLine.unsafeArgv, nil, NSStringFromClass(AppDelegate.self))
