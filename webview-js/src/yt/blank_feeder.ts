import { BLANK_VIDEO_MIME, buildBlankClusters, buildBlankInit } from './blank_webm';

/**
 * PROTOTYPE — seamless audio-only ("zero" quality) without MediaSource reattach.
 *
 * The old scheme let the player build an audio-only MediaSource (fakesb => no video
 * SourceBuffer) and force-reattached on every zero<->video switch, discarding all buffers (the
 * stall). New scheme: the video SourceBuffer ALWAYS exists (audio_only.ts patches base.js's
 * sourceopen handler to create it with a blank vp9 mime instead of skipping it for "fakesb"),
 * and while audio-only is active this feeder appends locally generated blank VP9 clusters
 * (~34 bytes / 10 s) covering wherever audio is buffered, so the media element never underruns
 * and the player's native seamless format-switch path handles both directions. Zero video
 * network bytes: the fake format still never fetches, and SABR still gets the audio-only flags.
 *
 * The `window.__ymBlankMime` hook doubles as the feature gate: when absent (no changeType
 * support), the base.js patches keep the legacy fakesb/reattach behavior.
 */
export function blankVideoFeeder(wvApi = window.ymixWvApi) {
  const MS = window.MediaSource;
  const supportsChangeType = !!(window.SourceBuffer && (window.SourceBuffer.prototype as any).changeType);
  if (!MS || !MS.prototype || !MS.prototype.addSourceBuffer || !supportsChangeType) return;

  (window as any).__ymBlankMime = function (mime: string) {
    return typeof mime === 'string' && mime.indexOf('fakesb') === 0 ? BLANK_VIDEO_MIME : mime;
  };

  let msRef: MediaSource | null = null;
  let videoSb: any = null;
  let audioSb: any = null;
  let curMime = '';
  let realMime = '';
  let blankInitAppended = false;
  let moviePlayer: any;

  const flog = (msg: string) => {
    window.handleYtInternalLog?.('info', 'blankfeed', msg);
    if (DEBUG) console.log('[blankfeed]', msg);
  };

  let leftZeroAt = 0; // >0: timestamp of the most recent zero -> video transition; -1: in zero
  const prefIsZero = () => {
    moviePlayer = moviePlayer || document.getElementById('movie_player');
    const zero = moviePlayer?.getUserPlaybackQualityPreference?.() === 'zero';
    if (zero) leftZeroAt = -1;
    else if (leftZeroAt === -1) leftZeroAt = Date.now();
    return zero;
  };

  // Fallback real video mime, for when the SB was born blank (video loaded while already
  // audio-only) so `realMime` was never captured from a prior real append: the largest video
  // adaptiveFormat the player selects from. changeType is guarded, so a near-miss just no-ops.
  const realMimeFromPlayer = (): string => {
    moviePlayer = moviePlayer || document.getElementById('movie_player');
    const fmts: any[] = moviePlayer?.getPlayerResponse?.()?.streamingData?.adaptiveFormats ?? [];
    let best: any;
    for (const f of fmts) {
      if (typeof f?.mimeType === 'string' && f.mimeType.indexOf('video/') === 0) {
        if (!best || (f.height ?? 0) > (best.height ?? 0)) best = f;
      }
    }
    return best?.mimeType ?? '';
  };

  // The base.js reattach-suppression predicate: true across a zero<->video switch, so the player
  // keeps one MediaSource and takes its in-place path instead of tearing it down. Covers BOTH
  // directions deterministically: pref==='zero' handles video->zero; a short grace window after
  // leaving zero handles zero->video regardless of whether the feeder has already restored the
  // real codec (otherwise the player's reattach decision races the feeder's changeType). On
  // zero->video the video track underruns briefly while real video streams into the existing SB;
  // audio is never interrupted.
  const LEAVE_ZERO_GRACE_MS = 2000;
  (window as any).__ymNoReattach = () => {
    if (prefIsZero() || curMime === BLANK_VIDEO_MIME) return true;
    return leftZeroAt > 0 && Date.now() - leftZeroAt < LEAVE_ZERO_GRACE_MS;
  };

  function trackSourceBuffer(ms: MediaSource, sb: any, mime: string) {
    if (typeof mime !== 'string') return;
    if (mime.indexOf('video/') === 0) {
      msRef = ms;
      videoSb = sb;
      curMime = mime;
      realMime = mime === BLANK_VIDEO_MIME ? realMime : mime;
      blankInitAppended = false;
      sb.__ymRawAppend = sb.appendBuffer;
      sb.__ymRawChangeType = sb.changeType;
      // The player appending real video while the SB is still typed blank (its own mime
      // bookkeeping doesn't know about our changeType) => restore the real type first.
      sb.appendBuffer = function (data: any) {
        // This is the player appending real content (the feeder bypasses via __ymRawAppend). If the
        // SB is still blank-typed (changeType path), restore the real codec first. Either way, a
        // real append means the next audio-only cycle must re-declare the blank track.
        if (curMime === BLANK_VIDEO_MIME && this === videoSb) {
          const restoreTo = realMime || realMimeFromPlayer();
          if (restoreTo) {
            try {
              sb.__ymRawChangeType.call(this, restoreTo);
              realMime = restoreTo;
              curMime = restoreTo;
              flog(`restore type ${restoreTo}`);
            } catch (e: any) {
              flog(`restore type failed: ${e?.message}`);
            }
          }
        }
        if (this === videoSb) blankInitAppended = false;
        return sb.__ymRawAppend.apply(this, arguments);
      };
      sb.changeType = function (t: string) {
        curMime = t;
        if (t !== BLANK_VIDEO_MIME) realMime = t;
        blankInitAppended = false;
        return sb.__ymRawChangeType.apply(this, arguments);
      };
      flog(`video sb ${mime}`);
    } else if (mime.indexOf('audio/') === 0) {
      audioSb = sb;
    }
  }

  const origAdd = MS.prototype.addSourceBuffer;
  MS.prototype.addSourceBuffer = function (mime: string) {
    const sb = origAdd.call(this, mime);
    try {
      trackSourceBuffer(this, sb, mime);
    } catch (e: any) {
      wvApi.reportError('blankfeed track failure: ' + e?.message);
    }
    return sb;
  };

  const audioOnlyActive = prefIsZero;

  function firstGap(sb: any, from: number, to: number): number {
    let cursor = from;
    const b = sb.buffered;
    for (let i = 0; i < b.length; i++) {
      if (b.start(i) <= cursor + 0.1 && b.end(i) > cursor) cursor = b.end(i);
    }
    return cursor >= to ? -1 : cursor;
  }

  function tick() {
    try {
      if (!videoSb || !audioSb || !msRef || msRef.readyState !== 'open') return;
      if (!audioOnlyActive()) return;
      if (videoSb.updating || audioSb.updating) return;
      const vid: HTMLVideoElement | null = document.querySelector('video');
      if (!vid || vid.error) return;
      const ct = vid.currentTime;
      // Audio buffered is the timeline truth (VOD and live alike): cover video wherever the
      // audio range around the playhead reaches. But never wait for audio — the player's append
      // pipeline runs the tracks in lockstep, so video must lead or both sides deadlock: always
      // keep at least a [ct, ct+30] window covered (capped by a known duration).
      let aEnd = -1;
      const ab = audioSb.buffered;
      for (let i = 0; i < ab.length; i++) {
        if (ab.end(i) > ct - 0.5) {
          aEnd = ab.end(i);
          break;
        }
      }
      const durCap = isFinite(msRef.duration) && msRef.duration > 0 ? msRef.duration : ct + 30;
      const target = Math.max(aEnd, Math.min(ct + 30, durCap));
      if (target <= ct + 0.1) return;
      const start = Math.max(0, ct - 1);
      const gapStart = firstGap(videoSb, start, target - 0.3);
      if (gapStart < 0) return;
      // When the real video is webm/VP9 (mobile), a plain VP9 blank keyframe is codec-compatible
      // with the vp09-typed SourceBuffer, so we append it WITHOUT changeType. No codec change means
      // none of base.js's reattach guards trip in either direction — zero<->video is naturally
      // seamless. Only cross-container reals (mp4 av01/avc1, desktop) need the changeType path,
      // which relies on the reattach-suppression patches.
      const blankIsCompatible = realMime.indexOf('video/webm') === 0 && /vp0?9/.test(realMime);
      if (!blankIsCompatible && curMime !== BLANK_VIDEO_MIME) {
        videoSb.changeType(BLANK_VIDEO_MIME); // wrapped: records curMime/blankInitAppended
        flog(`changeType blank (was ${realMime})`);
      }
      if (!blankInitAppended) {
        videoSb.__ymRawAppend.call(videoSb, buildBlankInit(
          isFinite(msRef.duration) ? msRef.duration * 1000 : undefined));
        blankInitAppended = true;
        return; // clusters next tick, appendBuffer is async
      }
      const fromMs = Math.max(0, (gapStart - 0.5) * 1000);
      const toMs = Math.min(target * 1000, fromMs + 3600 * 1000);
      videoSb.__ymRawAppend.call(videoSb, buildBlankClusters(fromMs, toMs));
      flog(`blank ${Math.round(fromMs / 1000)}-${Math.round(toMs / 1000)}s ct=${Math.round(ct)}`);
    } catch (e: any) {
      flog(`tick error: ${e?.message}`);
    }
  }

  setInterval(tick, 500);
}
