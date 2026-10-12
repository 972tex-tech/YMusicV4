import AVFoundation
import SwiftUI
import UIKit
import YMusicApp

// The iOS product shell: hosts the shared Compose UI (MainViewControllerKt.MainViewController from the
// :commonUi YMusicApp framework), which builds the AppGraph over the AVFoundation local + WKWebView
// YouTube engines and runs the same App as the desktop/Android shells.

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Let audio keep playing when the app is backgrounded/locked.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        IosPipHostKt.iosSetPipPrepare {
            IosPip.shared.prepare()
        }
    }

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
                .onAppear { IosPip.shared.prepare() }
                .onOpenURL { url in IosLinksKt.iosOpenUrl(url: url.absoluteString) }
                .onChange(of: scenePhase) { phase in
                    switch phase {
                    case .inactive:
                        // Start while still presenting so PiP is not a still of the last frame.
                        IosPip.shared.startIfEligible()
                    case .background:
                        IosPip.shared.noteLeftWithoutPip()
                    case .active:
                        IosPipHostKt.iosOnEnterForeground()
                        IosPip.shared.prepare()
                        DispatchQueue.main.async {
                            IosPip.shared.recoverIfNeeded()
                        }
                    default:
                        break
                    }
                }
        }
    }
}
