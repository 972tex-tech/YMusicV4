import AVFoundation
import AVKit
import UIKit
import YMusicApp

/// Picture-in-picture over the MSE [AVSampleBufferDisplayLayer].
final class IosPip: NSObject, AVPictureInPictureControllerDelegate, AVPictureInPictureSampleBufferPlaybackDelegate {
    static let shared = IosPip()

    private var controller: AVPictureInPictureController?
    /// Set in restoreUserInterface… (expand). X-close never calls that method.
    private var restoringFromPip = false
    var isActive: Bool { controller?.isPictureInPictureActive ?? false }

    func syncEligible() {
        guard #available(iOS 15.0, *), AVPictureInPictureController.isPictureInPictureSupported() else { return }
        guard let layer = IosPipHostKt.iosPipDisplayLayer() else { return }
        if controller == nil {
            let source = AVPictureInPictureController.ContentSource(
                sampleBufferDisplayLayer: layer,
                playbackDelegate: self,
            )
            controller = AVPictureInPictureController(contentSource: source)
            controller?.delegate = self
        }
        let ok = IosPipHostKt.iosPipShouldAutoEnter()
        controller?.canStartPictureInPictureAutomaticallyFromInline = ok
    }

    func startIfEligible() {
        syncEligible()
        guard IosPipHostKt.iosPipShouldAutoEnter() else { return }
        if controller?.isPictureInPictureActive != true {
            controller?.startPictureInPicture()
        }
    }

    func pictureInPictureControllerDidStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        IosPipHostKt.iosOnPipStarted()
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void,
    ) {
        restoringFromPip = true
        completionHandler(true)
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        let restore = restoringFromPip
        restoringFromPip = false
        let foreground = restore || UIApplication.shared.applicationState == .active
        IosPipHostKt.iosOnPipStopped(appInForeground: foreground)
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        setPlaying playing: Bool,
    ) {
        if playing { IosPipHostKt.iosPipPlay() } else { IosPipHostKt.iosPipPause() }
    }

    func pictureInPictureControllerTimeRangeForPlayback(
        _ pictureInPictureController: AVPictureInPictureController,
    ) -> CMTimeRange {
        CMTimeRange(start: .zero, duration: .positiveInfinity)
    }

    func pictureInPictureControllerIsPlaybackPaused(
        _ pictureInPictureController: AVPictureInPictureController,
    ) -> Bool {
        !IosPipHostKt.iosPipIsPlaying()
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        didTransitionToRenderSize newRenderSize: CMVideoDimensions,
    ) {}

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        skipByInterval skipInterval: CMTime,
        completion completionHandler: @escaping () -> Void,
    ) {
        completionHandler()
    }
}
