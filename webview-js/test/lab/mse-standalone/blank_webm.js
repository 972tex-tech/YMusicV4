// Minimal WebM (EBML) muxer for blank VP9 video: init segment + clusters, each cluster one
// black keyframe in a BlockGroup with an explicit BlockDuration so MSE reports continuous
// buffered ranges. Timecode scale = 1ms. Lab prototype for the audio-only blank feeder.
(function () {
  // 16x16 black VP9 keyframe (ffmpeg libvpx-vp9), 28 bytes.
  var BLANK_VP9_FRAME = new Uint8Array([
    0x82, 0x49, 0x83, 0x42, 0x00, 0x00, 0xf0, 0x00, 0xf6, 0x00, 0x38, 0x24, 0x1c, 0x18, 0x4a, 0x00,
    0x00, 0x30, 0x60, 0x00, 0x00, 0x10, 0xbf, 0xff, 0xfd, 0x48, 0x8c, 0x00,
  ]);

  function pushU(bytes, val, n) {
    for (var i = n - 1; i >= 0; i--) bytes.push((val >>> (8 * i)) & 0xff);
  }
  // EBML vint for element sizes (8-byte form used only for large/unknown).
  function vint(size) {
    var out = [];
    if (size < 0x7f) out.push(0x80 | size);
    else if (size < 0x3fff) {
      out.push(0x40 | (size >> 8), size & 0xff);
    } else if (size < 0x1fffff) {
      out.push(0x20 | (size >> 16), (size >> 8) & 0xff, size & 0xff);
    } else {
      out.push(0x10 | (size >>> 24), (size >> 16) & 0xff, (size >> 8) & 0xff, size & 0xff);
    }
    return out;
  }
  function el(idBytes, payload) {
    var out = idBytes.slice();
    out.push.apply(out, vint(payload.length));
    out.push.apply(out, payload);
    return out;
  }
  function uintEl(idBytes, val) {
    var payload = [];
    var tmp = val;
    var n = 1;
    while (tmp >= 256) {
      tmp = Math.floor(tmp / 256);
      n++;
    }
    for (var i = n - 1; i >= 0; i--) payload.push(Math.floor(val / Math.pow(256, i)) % 256);
    return el(idBytes, payload);
  }
  function floatEl(idBytes, val) {
    var buf = new DataView(new ArrayBuffer(8));
    buf.setFloat64(0, val);
    var payload = [];
    for (var i = 0; i < 8; i++) payload.push(buf.getUint8(i));
    return el(idBytes, payload);
  }
  function strEl(idBytes, s) {
    var payload = [];
    for (var i = 0; i < s.length; i++) payload.push(s.charCodeAt(i));
    return el(idBytes, payload);
  }

  // Element IDs.
  var EBML = [0x1a, 0x45, 0xdf, 0xa3],
    SEGMENT = [0x18, 0x53, 0x80, 0x67];
  var INFO = [0x15, 0x49, 0xa9, 0x66],
    TRACKS = [0x16, 0x54, 0xae, 0x6b];
  var CLUSTER = [0x1f, 0x43, 0xb6, 0x75];

  function buildInit(durationMs) {
    var ebml = el(
      EBML,
      [].concat(
        uintEl([0x42, 0x86], 1), // EBMLVersion
        uintEl([0x42, 0xf7], 1), // EBMLReadVersion
        uintEl([0x42, 0xf2], 4), // EBMLMaxIDLength
        uintEl([0x42, 0xf3], 8), // EBMLMaxSizeLength
        strEl([0x42, 0x82], "webm"), // DocType
        uintEl([0x42, 0x87], 4), // DocTypeVersion
        uintEl([0x42, 0x85], 2), // DocTypeReadVersion
      ),
    );
    var info = el(
      INFO,
      [].concat(
        uintEl([0x2a, 0xd7, 0xb1], 1000000), // TimecodeScale: 1ms
        floatEl([0x44, 0x89], durationMs), // Duration (ms)
        strEl([0x4d, 0x80], "ymblank"), // MuxingApp
        strEl([0x57, 0x41], "ymblank"), // WritingApp
      ),
    );
    var video = el(
      [0xe0],
      [].concat(
        // Video settings
        uintEl([0xb0], 16), // PixelWidth
        uintEl([0xba], 16), // PixelHeight
      ),
    );
    var track = el(
      [0xae],
      [].concat(
        // TrackEntry
        uintEl([0xd7], 1), // TrackNumber
        uintEl([0x73, 0xc5], 1), // TrackUID
        uintEl([0x83], 1), // TrackType: video
        uintEl([0x9c], 0), // FlagLacing
        strEl([0x86], "V_VP9"), // CodecID
        video,
      ),
    );
    var tracks = el(TRACKS, track);
    // Segment with unknown size (streaming): 0x01 + 7x 0xFF.
    var seg = SEGMENT.concat([0x01, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff], info, tracks);
    return new Uint8Array(ebml.concat(seg));
  }

  // One cluster: Timecode=tMs, BlockGroup{Block(track1, relT=0, keyframe), BlockDuration=durMs}.
  function buildCluster(tMs, durMs) {
    var frame = BLANK_VP9_FRAME;
    var blockPayload = [0x81, 0x00, 0x00, 0x00]; // track vint 1, rel timecode 0, flags 0
    for (var i = 0; i < frame.length; i++) blockPayload.push(frame[i]);
    var blockGroup = el(
      [0xa0],
      [].concat(
        el([0xa1], blockPayload), // Block
        uintEl([0x9b], durMs), // BlockDuration
      ),
    );
    var payload = [].concat(uintEl([0xe7], tMs), blockGroup);
    return new Uint8Array(el(CLUSTER, payload));
  }

  // Blank media covering [fromMs, toMs) as clusters of clusterMs.
  function buildClusters(fromMs, toMs, clusterMs) {
    clusterMs = clusterMs || 10000;
    var parts = [],
      total = 0;
    for (var t = fromMs; t < toMs; t += clusterMs) {
      var dur = Math.min(clusterMs, toMs - t);
      var c = buildCluster(Math.round(t), Math.round(dur));
      parts.push(c);
      total += c.length;
    }
    var out = new Uint8Array(total),
      off = 0;
    for (var i = 0; i < parts.length; i++) {
      out.set(parts[i], off);
      off += parts[i].length;
    }
    return out;
  }

  window.blankWebm = { buildInit: buildInit, buildClusters: buildClusters };
})();
