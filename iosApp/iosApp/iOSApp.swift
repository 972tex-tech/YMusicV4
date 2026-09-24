import AVFoundation
import SwiftUI
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
    @State private var wasBackgrounded = false

    init() {
        // Let audio keep playing when the app is backgrounded/locked.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
                .onChange(of: scenePhase) { phase in
                    switch phase {
                    case .background:
                        // GPU/IOSurface is torn down only once we actually leave the screen.
                        wasBackgrounded = true
                    case .active:
                        // Notification Center / Control Center is .inactive, not .background —
                        // reopening VT there blacks the picture for 1–2s while the app is still up.
                        if wasBackgrounded {
                            wasBackgrounded = false
                            DispatchQueue.main.async {
                                IosMseSurfaceKt.iosMseBecameActive()
                            }
                        }
                    default:
                        break
                    }
                }
        }
    }
}
