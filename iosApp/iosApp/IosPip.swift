import AVFoundation
import AVKit
import CoreMedia
import UIKit
import YMusicApp

/// Picture-in-picture over the MSE [AVSampleBufferDisplayLayer].
final class IosPip: NSObject, AVPictureInPictureControllerDelegate, AVPictureInPictureSampleBufferPlaybackDelegate {
    static let shared = IosPip()

    private var controller: AVPictureInPictureController?
    /// Set in restoreUserInterface… (expand). X-close never calls that method.
    private var restoringFromPip = false
    private var stateTimer: Timer?
    var isActive: Bool { controller?.isPictureInPictureActive ?? false }

    /// Build the controller while still in the foreground so the first leave is live frames.
    func prepare() {
        guard #available(iOS 15.0, *), AVPictureInPictureController.isPictureInPictureSupported() else { return }
        guard let layer = IosPipHostKt.iosPipDisplayLayer() else { return }
        if controller == nil {
            let source = AVPictureInPictureController.ContentSource(
                sampleBufferDisplayLayer: layer,
                playbackDelegate: self,
            )
            let pip = AVPictureInPictureController(contentSource: source)
            pip.delegate = self
            pip.requiresLinearPlayback = false
            controller = pip
        }
        syncTimebase(at: IosPipHostKt.iosPipPositionSeconds())
        let ok = IosPipHostKt.iosPipShouldAutoEnter()
        controller?.canStartPictureInPictureAutomaticallyFromInline = ok
        if !ok, controller?.isPictureInPictureActive == true {
            controller?.stopPictureInPicture()
        }
    }

    func startIfEligible() {
        prepare()
        guard IosPipHostKt.iosPipShouldAutoEnter() else { return }
        guard controller?.isPictureInPictureActive != true else { return }
        if controller?.isPictureInPicturePossible == true {
            controller?.startPictureInPicture()
        } else {
            DispatchQueue.main.async { [weak self] in
                guard let self, IosPipHostKt.iosPipShouldAutoEnter() else { return }
                self.controller?.startPictureInPicture()
            }
        }
    }

    func pictureInPictureControllerDidStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        IosPipHostKt.iosOnPipStarted()
        syncTimebase(at: IosPipHostKt.iosPipPositionSeconds())
        pictureInPictureController.invalidatePlaybackState()
        stateTimer?.invalidate()
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self, weak pictureInPictureController] _ in
            self?.syncTimebase(at: IosPipHostKt.iosPipPositionSeconds())
            pictureInPictureController?.invalidatePlaybackState()
        }
        RunLoop.main.add(timer, forMode: .common)
        stateTimer = timer
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void,
    ) {
        restoringFromPip = true
        completionHandler(true)
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        stateTimer?.invalidate()
        stateTimer = nil
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
        syncTimebase(at: IosPipHostKt.iosPipPositionSeconds())
        pictureInPictureController.invalidatePlaybackState()
    }

    func pictureInPictureControllerTimeRangeForPlayback(
        _ pictureInPictureController: AVPictureInPictureController,
    ) -> CMTimeRange {
        let dur = IosPipHostKt.iosPipDurationSeconds()
        if dur <= 0 {
            return CMTimeRange(start: .zero, duration: .positiveInfinity)
        }
        return CMTimeRange(start: .zero, duration: CMTime(seconds: dur, preferredTimescale: 600))
    }

    func pictureInPictureControllerIsPlaybackPaused(
        _ pictureInPictureController: AVPictureInPictureController,
    ) -> Bool {
        !IosPipHostKt.iosPipIsPlaying()
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        didTransitionToRenderSize newRenderSize: CMVideoDimensions,
    ) {
        IosPipHostKt.iosPipSetRenderSize(width: Int32(newRenderSize.width), height: Int32(newRenderSize.height))
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        skipByInterval skipInterval: CMTime,
        completion completionHandler: @escaping () -> Void,
    ) {
        let at = IosPipHostKt.iosPipSeekBySeconds(delta: CMTimeGetSeconds(skipInterval))
        syncTimebase(at: at)
        controller?.invalidatePlaybackState()
        completionHandler()
    }

    /// PiP draws the playhead from this layer's timebase (WWDC21). DisplayImmediately
    /// still presents frames; the clock is only for the chrome.
    private func syncTimebase(at seconds: Double) {
        guard let layer = IosPipHostKt.iosPipDisplayLayer() else { return }
        if layer.controlTimebase == nil {
            var tb: CMTimebase?
            CMTimebaseCreateWithSourceClock(kCFAllocatorDefault, CMClockGetHostTimeClock(), &tb)
            if let tb {
                CMTimebaseSetRate(tb, rate: 0)
                layer.controlTimebase = tb
            }
        }
        guard let tb = layer.controlTimebase else { return }
        CMTimebaseSetTime(tb, time: CMTime(seconds: max(0, seconds), preferredTimescale: 600))
        CMTimebaseSetRate(tb, rate: IosPipHostKt.iosPipIsPlaying() ? 1.0 : 0.0)
    }
}
