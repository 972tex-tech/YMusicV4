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
    init() {
        // Let audio (YouTube WebView / AVPlayer) keep playing when the app is backgrounded/locked.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea() // Compose draws edge-to-edge and handles its own insets.
        }
    }
}
