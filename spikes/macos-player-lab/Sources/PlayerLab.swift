import SwiftUI
import AVFoundation
import AppKit
import Player

// Native macOS player lab — drives :player through Player.framework, no Compose, no iOS simulator.
// AVFoundation LocalPlayer.

@main
struct PlayerLabApp: App {
    var body: some Scene {
        WindowGroup("YMusic · :player (macOS native)") {
            RootView()
        }
    }
}

struct RootView: View {
    var body: some View {
        LocalView()
            .padding(8)
            .frame(minWidth: 520, minHeight: 640)
    }
}

// MARK: - Local (AVFoundation)

final class PlayerModel: ObservableObject {
    let player: LocalPlayer =
        LocalPlayerFactory_appleKt.createLocalPlayer(context: LocalPlayerContext())

    @Published var status: PlaybackStatus
    @Published var progress: Player.Progress
    // All settings travel through one PlaybackConfig, built via the Swift-friendly factory.
    @Published var config: PlaybackConfig = AppleInteropKt.playbackConfig(volume: 1.0, speed: 1.0, audioOnly: false)
    private var timer: Timer?

    var avPlayer: AVPlayer? { AppleInteropKt.avPlayer(player) }

    init() {
        status = AppleInteropKt.currentStatus(player)
        progress = AppleInteropKt.currentProgress(player)
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            self.status = AppleInteropKt.currentStatus(self.player)
            self.progress = AppleInteropKt.currentProgress(self.player)
        }
    }

    func open() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.title = "Choose a media file"
        if panel.runModal() == .OK, let url = panel.url {
            player.load(
                source: PlayableItemLocalUri(uri: url.path), startPositionMs: 0, playWhenReady: true,
                config: config)
        }
    }

    func setAudioOnly(_ on: Bool) {
        config = AppleInteropKt.playbackConfig(volume: config.volume, speed: config.speed, audioOnly: on)
        player.setConfig(config: config)
    }

    func setVolume(_ v: Float) {
        config = AppleInteropKt.playbackConfig(volume: v, speed: config.speed, audioOnly: config.audioOnly)
        player.setConfig(config: config)
    }

    func seek(fraction: Double) {
        let d = progress.durationMs
        if d > 0 { player.seekTo(positionMs: Int64(fraction * Double(d))) }
    }
}

struct LocalView: View {
    @StateObject private var model = PlayerModel()

    private var duration: Int64 { model.progress.durationMs }
    private var position: Int64 { model.progress.positionMs }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("LocalPlayer — AVFoundation").font(.headline)

            Button("Open file…") { model.open() }

            Text("State: \(model.status.state.name)").font(.subheadline)
            if let e = model.status.error {
                Text("Load failed (\(e.code))").foregroundColor(.red)
            }

            HStack {
                Toggle("Audio-only", isOn: Binding(
                    get: { model.config.audioOnly },
                    set: { model.setAudioOnly($0) }))
                Spacer()
                if let v = model.status.videoSize {
                    Text("\(v.width)×\(v.height)").foregroundColor(.secondary)
                }
            }

            if !model.config.audioOnly, let av = model.avPlayer {
                VideoLayerView(player: av)
                    .frame(height: 240)
                    .background(Color.black)
                    .cornerRadius(4)
            }

            transport(
                position: position, duration: duration,
                seek: { model.seek(fraction: $0) },
                play: { model.player.play() }, pause: { model.player.pause() },
                back: { model.player.seekTo(positionMs: position - 10_000) },
                fwd: { model.player.seekTo(positionMs: position + 10_000) },
                stop: { model.player.stop() })

            HStack {
                Text("Volume")
                Slider(value: Binding(
                    get: { Double(model.config.volume) },
                    set: { model.setVolume(Float($0)) }), in: 0...1)
            }
            Spacer()
        }
        .padding(20)
    }
}

// MARK: - Shared bits

/// Position slider + play/pause/±10s/stop.
@ViewBuilder
func transport(
    position: Int64, duration: Int64,
    seek: @escaping (Double) -> Void,
    play: @escaping () -> Void, pause: @escaping () -> Void,
    back: @escaping () -> Void, fwd: @escaping () -> Void, stop: @escaping () -> Void
) -> some View {
    VStack(spacing: 8) {
        HStack {
            Text(fmtMs(position)).monospacedDigit()
            Slider(value: Binding(
                get: { duration > 0 ? Double(position) / Double(duration) : 0 },
                set: { seek($0) }), in: 0...1)
            Text(duration > 0 ? fmtMs(duration) : "--:--").monospacedDigit()
        }
        HStack(spacing: 8) {
            Button("Play", action: play)
            Button("Pause", action: pause)
            Button("−10s", action: back)
            Button("+10s", action: fwd)
            Button("Stop", action: stop)
        }
    }
}

func fmtMs(_ ms: Int64) -> String {
    let total = Int(ms / 1000)
    return String(format: "%d:%02d", total / 60, total % 60)
}

/// An `NSView` hosting an `AVPlayerLayer`, bound to the backend's `AVPlayer`.
struct VideoLayerView: NSViewRepresentable {
    let player: AVPlayer

    func makeNSView(context: Context) -> NSView {
        let view = PlayerNSView()
        view.playerLayer.player = player
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        (nsView as? PlayerNSView)?.playerLayer.player = player
    }
}

final class PlayerNSView: NSView {
    let playerLayer = AVPlayerLayer()

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        let root = CALayer()
        root.backgroundColor = NSColor.black.cgColor
        layer = root
        playerLayer.videoGravity = .resizeAspect
        playerLayer.frame = bounds
        root.addSublayer(playerLayer)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func layout() {
        super.layout()
        playerLayer.frame = bounds
    }
}
