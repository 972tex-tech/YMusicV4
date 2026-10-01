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
    private var parkIfNoPip: DispatchWorkItem?
    /// Set when we leave without an active PiP session; recover on the next foreground.
    private var needsRecover = false
    var isActive: Bool { controller?.isPictureInPictureActive ?? false }

    private override init() {
        super.init()
        NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification,
            object: nil,
            queue: .main,
        ) { [weak self] _ in
            self?.startIfEligible()
        }
    }

    /// Attach the controller while video is playing in the foreground. Auto-enter
    /// is only armed for Now Playing / fullscreen. Audio-only drops the controller.
    func prepare() {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in self?.prepare() }
            return
        }
        guard #available(iOS 15.0, *), AVPictureInPictureController.isPictureInPictureSupported() else { return }
        if !IosPipHostKt.iosPipPlayingVideo() {
            dropControllerIfIdle()
            return
        }
        guard let layer = IosPipHostKt.iosPipDisplayLayer() else { return }
        // WWDC21: the layer timebase must exist before ContentSource, or the
        // first startPictureInPicture is not possible.
        syncTimebase(at: IosPipHostKt.iosPipPositionSeconds())
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
        let auto = IosPipHostKt.iosPipShouldAutoEnter()
        controller?.canStartPictureInPictureAutomaticallyFromInline = auto
        controller?.invalidatePlaybackState()
        if auto { armUntilPossible() }
    }

    func startIfEligible() {
        if !IosPipHostKt.iosPipPlayingVideo() {
            dropControllerIfIdle()
            return
        }
        prepare()
        guard IosPipHostKt.iosPipShouldAutoEnter() else { return }
        guard controller?.isPictureInPictureActive != true else { return }
        // Auto-enter is already armed. start() only if the system reports possible;
        // calling it on a cold controller during Home misses the first leave.
        if controller?.isPictureInPicturePossible == true {
            controller?.startPictureInPicture()
        }
    }

    private func dropControllerIfIdle() {
        armTimer?.invalidate()
        armTimer = nil
        controller?.canStartPictureInPictureAutomaticallyFromInline = false
        guard controller?.isPictureInPictureActive != true else { return }
        controller?.delegate = nil
        controller = nil
    }

    /// Keep the playhead inside the reported range until iOS flips possible, while still active.
    private var armTimer: Timer?
    private func armUntilPossible() {
        armTimer?.invalidate()
        armTimer = nil
        guard controller?.isPictureInPicturePossible != true else { return }
        let timer = Timer(timeInterval: 0.2, repeats: true) { [weak self] t in
            guard let self else { t.invalidate(); return }
            guard UIApplication.shared.applicationState == .active,
                  IosPipHostKt.iosPipPlayingVideo()
            else {
                t.invalidate()
                return
            }
            self.syncTimebase(at: IosPipHostKt.iosPipPositionSeconds())
            self.controller?.invalidatePlaybackState()
            if self.controller?.isPictureInPicturePossible == true {
                t.invalidate()
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        armTimer = timer
    }

    /// After a leave with no PiP, park VT so foreground recover can unstick the last frame.
    func noteLeftWithoutPip() {
        if isActive {
            parkIfNoPip?.cancel()
            parkIfNoPip = nil
            needsRecover = false
            return
        }
        needsRecover = true
        parkIfNoPip?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.isActive else { return }
            IosMseSurfaceKt.iosMseWentBackground()
            if !IosPipHostKt.iosPipShouldAutoEnter() {
                IosPipHostKt.iosOnLeaveWithoutPip()
            }
        }
        parkIfNoPip = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: work)
    }

    func recoverIfNeeded() {
        parkIfNoPip?.cancel()
        parkIfNoPip = nil
        if needsRecover {
            needsRecover = false
            IosMseSurfaceKt.iosMseWentBackground()
        }
        IosMseSurfaceKt.iosMseBecameActive()
    }

    func pictureInPictureControllerDidStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        armTimer?.invalidate()
        armTimer = nil
        parkIfNoPip?.cancel()
        parkIfNoPip = nil
        needsRecover = false
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
            CMTimebaseCreateWithSourceClock(
                allocator: kCFAllocatorDefault,
                sourceClock: CMClockGetHostTimeClock(),
                timebaseOut: &tb,
            )
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
