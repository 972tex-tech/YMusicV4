// MSE instrumentation, installed via CDP addScriptToEvaluateOnNewDocument (document-start,
// before the ymusic prelude/bundle and any YouTube script). ES5-ish, Chrome-only lab code.
(function () {
  if (window.__mse) return;
  var t0 = Date.now();
  var log = [];
  var msSeq = 0,
    sbSeq = 0;
  window.__mse = { log: log, sources: [] };
  function rec(ev, detail) {
    var e = { t: (Date.now() - t0) / 1000, ev: ev };
    if (detail) for (var k in detail) e[k] = detail[k];
    log.push(e);
    try {
      console.log("[mse]", JSON.stringify(e));
    } catch (x) {}
  }
  window.__mseRec = rec;

  function fmtRanges(b) {
    var out = [];
    try {
      for (var i = 0; i < b.length; i++)
        out.push(b.start(i).toFixed(2) + "-" + b.end(i).toFixed(2));
    } catch (x) {
      return "err";
    }
    return out.join(",");
  }
  window.__fmtRanges = fmtRanges;

  var OrigMS = window.MediaSource;
  if (!OrigMS) return;

  function wrapSB(sb, msId, mime) {
    var id = "sb" + ++sbSeq;
    sb.__mseId = id;
    sb.__mime = mime;
    rec("addSourceBuffer", { ms: msId, sb: id, mime: mime });
    var origAppend = sb.appendBuffer;
    sb.appendBuffer = function (data) {
      var len = data && (data.byteLength != null ? data.byteLength : data.length);
      rec("append", { sb: id, mime: sb.__mime, bytes: len, to: sb.timestampOffset });
      return origAppend.apply(this, arguments);
    };
    var origRemove = sb.remove;
    sb.remove = function (s, e) {
      rec("remove", { sb: id, mime: sb.__mime, start: s, end: e });
      return origRemove.apply(this, arguments);
    };
    var origAbort = sb.abort;
    sb.abort = function () {
      rec("abort", { sb: id, mime: sb.__mime });
      return origAbort.apply(this, arguments);
    };
    if (sb.changeType) {
      var origChange = sb.changeType;
      sb.changeType = function (type) {
        rec("changeType", { sb: id, from: sb.__mime, to: type });
        sb.__mime = type;
        return origChange.apply(this, arguments);
      };
    }
    return sb;
  }

  window.MediaSource = function () {
    var ms = new OrigMS();
    var id = "ms" + ++msSeq;
    ms.__mseId = id;
    window.__mse.sources.push(ms);
    rec("newMediaSource", { ms: id });
    ms.addEventListener("sourceopen", function () {
      rec("sourceopen", { ms: id });
    });
    ms.addEventListener("sourceended", function () {
      rec("sourceended", { ms: id });
    });
    ms.addEventListener("sourceclose", function () {
      rec("sourceclose", { ms: id });
    });
    var origAdd = ms.addSourceBuffer;
    ms.addSourceBuffer = function (mime) {
      var sb = origAdd.call(this, mime);
      return wrapSB(sb, id, mime);
    };
    var origRemoveSB = ms.removeSourceBuffer;
    ms.removeSourceBuffer = function (sb) {
      rec("removeSourceBuffer", { ms: id, sb: sb && sb.__mseId });
      return origRemoveSB.apply(this, arguments);
    };
    var origEOS = ms.endOfStream;
    ms.endOfStream = function (err) {
      rec("endOfStream", { ms: id, error: err });
      return origEOS.apply(this, arguments);
    };
    return ms;
  };
  window.MediaSource.prototype = OrigMS.prototype;
  window.MediaSource.isTypeSupported = OrigMS.isTypeSupported.bind(OrigMS);
  // Static props some code reads:
  for (var k in OrigMS) {
    if (!(k in window.MediaSource)) {
      try {
        window.MediaSource[k] = OrigMS[k];
      } catch (x) {}
    }
  }

  // Media element event probe (attach lazily once a <video> exists).
  var hookVideo = function () {
    var v = document.querySelector("video");
    if (!v) {
      setTimeout(hookVideo, 500);
      return;
    }
    if (v.__mseHooked) return;
    v.__mseHooked = true;
    [
      "loadstart",
      "loadedmetadata",
      "canplay",
      "playing",
      "waiting",
      "stalled",
      "seeking",
      "seeked",
      "pause",
      "ended",
      "emptied",
      "error",
      "durationchange",
    ].forEach(function (evName) {
      v.addEventListener(evName, function () {
        rec("video." + evName, { ct: v.currentTime, rs: v.readyState, buf: fmtRanges(v.buffered) });
      });
    });
    rec("videoElementHooked", {});
  };
  setTimeout(hookVideo, 500);

  // Snapshot used by the CDP poller.
  window.__mseSnap = function () {
    var v = document.querySelector("video");
    var p = document.getElementById("movie_player");
    var out = {
      ct: v ? v.currentTime : -1,
      rs: v ? v.readyState : -1,
      paused: v ? v.paused : null,
      vw: v ? v.videoWidth : -1,
      state: p && p.getPlayerState ? p.getPlayerState() : null,
      q: p && p.getPlaybackQuality ? p.getPlaybackQuality() : null,
      pref: p && p.getUserPlaybackQualityPreference ? p.getUserPlaybackQualityPreference() : null,
      sbs: [],
    };
    var srcs = window.__mse.sources;
    var ms = srcs.length ? srcs[srcs.length - 1] : null;
    if (ms) {
      out.ms = ms.__mseId + ":" + ms.readyState;
      try {
        for (var i = 0; i < ms.sourceBuffers.length; i++) {
          var sb = ms.sourceBuffers[i];
          out.sbs.push({
            id: sb.__mseId,
            mime: (sb.__mime || "").slice(0, 30),
            buf: fmtRanges(sb.buffered),
          });
        }
      } catch (x) {}
    }
    return JSON.stringify(out);
  };
})();
