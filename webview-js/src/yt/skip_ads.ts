// noinspection JSUnusedLocalSymbols
import { processSabrRequestBody } from './proto_buff_utils';
import { deepClone, makeBase64UrlSafe, proxyConstructor, proxyMethodPolyfill, u8ToBase64 } from './utils';
import './bot_guards';
import 'url-search-params-polyfill';

let reported403 = false;

export function skipAds(wvApi = window.ymixWvApi): void {
  // SABR caching is gated on window.enablePlayerCache (below) plus this per-platform version floor:
  // desktop (jvm) and iOS (darwin) support it at any app version; Android keeps the legacy 4453 SABR floor.
  const platform = window.ymixInitConfig.platform;
  const isJvm = platform === 'jvm';
  const isIos = platform === 'darwin';
  const isAndroid = platform === 'android';
  const mainAppVer = window.mainAppVer ?? 0;
  const canCacheSabr =
    (isJvm && mainAppVer >= 1) ||
    (isIos && mainAppVer >= 1) ||
    (isAndroid && mainAppVer >= 4453);

  wvApi.registerScriptTransformer(function (source: string, url?: string) {
    if (url == null && source.includes('var ytInitialPlayerResponse')) {
      const transformInitPlayerRes = 'transformInitPlayerRes';
      (window as any)[transformInitPlayerRes] = transformPlayerResponse;
      source = source + ';\n' + `ytInitialPlayerResponse = window.${transformInitPlayerRes}(ytInitialPlayerResponse);`;
    }
    return source;
  });

  function objPut(obj: any, path: string, value: any) {
    if (!obj) return obj;
    const paths = path.split('.');
    let tmp = obj;
    for (let i = 0; i < paths.length - 1; i++) {
      const key = paths[i];
      tmp = tmp[key] ?? (tmp[key] = {});
    }
    tmp[paths[paths.length - 1]] = value;
    return obj;
  }

  function isPlayerApiUrl(url: string) {
    return url && /\/player(\?|$)/.test(url);
  }

  function isTimedTextApiUrl(url?: string) {
    return url && url.includes('/api/timedtext');
  }

  function decryptUrlSigAndN(url: string) {
    const [urlWithoutQuery, urlQuery] = url.split('?', 2);
    if (!urlWithoutQuery || !urlQuery) return url;
    const searchParams = new URLSearchParams(urlQuery);
    const sParam = searchParams.get('s');
    if (sParam && window.ymixSigDe) {
      searchParams.delete('s');
      const sp = searchParams.get('sp') || 'sig';
      const res = window.ymixSigDe('', sp, encodeURIComponent(sParam));
      searchParams.set(sp, res.get(sp));
    }
    const nParam = searchParams.get('n');
    if (nParam && window.ymixNDe) {
      searchParams.set('n', window.ymixNDe(nParam));
      searchParams.set('_nok', '1');
    }
    return urlWithoutQuery + '?' + searchParams.toString();
  }

  function decryptStreamingFormatCipher(cipherStr: URLSearchParams) {
    const cipher = new URLSearchParams(cipherStr);
    const sParam = cipher.get('s');
    if (sParam && window.ymixSigDe) {
      cipher.delete('s');
      const sp = cipher.get('sp') || 'sig';
      const res = window.ymixSigDe('', sp, encodeURIComponent(sParam));
      cipher.set(sp, res.get(sp));
    }
    const url = cipher.get('url');
    if (url) {
      cipher.set('url', decryptUrlSigAndN(url));
    }
    return cipher.toString();
  }

  function decryptStreamingFormat(fmt: any) {
    if (fmt.cipher) fmt.cipher = decryptStreamingFormatCipher(fmt.cipher);
    if (fmt.signatureCipher) fmt.signatureCipher = decryptStreamingFormatCipher(fmt.signatureCipher);
    if (fmt.url) fmt.url = decryptUrlSigAndN(fmt.url);
  }

  function transformPlayerResponse(playerJson: any) {
    if (window.customUnplayableError && playerJson.playabilityStatus?.status == 'UNPLAYABLE') {
      const currentErrorText = playerJson.playabilityStatus.errorScreen?.playerErrorMessageRenderer?.reason?.simpleText;
      objPut(playerJson.playabilityStatus, 'errorScreen.playerErrorMessageRenderer.reason.simpleText',
        '(YMusic: Please try logging out or using a different account. YouTube may have shadow-banned your account/IP—this can take up to a week to resolve.\nIt likely a bug of YouTube system, they even banned YouTubePremium accounts from using their official apps as well.)'
        + (currentErrorText ? '\n' + currentErrorText : ''));
    }
    const responseForCache = { ...playerJson };
    if (responseForCache.streamingData) {
      responseForCache.streamingData = deepClone(playerJson.streamingData);
      responseForCache.streamingData.adaptiveFormats?.forEach(decryptStreamingFormat);
      responseForCache.streamingData.formats?.forEach(decryptStreamingFormat);
      if (responseForCache.streamingData.serverAbrStreamingUrl) {
        responseForCache.streamingData.serverAbrStreamingUrl = decryptUrlSigAndN(responseForCache.streamingData.serverAbrStreamingUrl);
      }
    }
    if (responseForCache.captions?.playerCaptionsTracklistRenderer?.captionTracks) {
      const videoId = responseForCache.videoDetails.videoId;
      let poToken = videoId?.length == 11 ? window.generatePoTokenForVideoSync(videoId) : undefined;
      if (poToken) poToken = makeBase64UrlSafe(poToken);
      // Clone before mutating so the timedtext baseUrls in the cached (download-facing) response carry the
      // decoded signature/n params + a bound pot, without touching the player's own live response.
      responseForCache.captions = {
        ...responseForCache.captions,
        playerCaptionsTracklistRenderer: {
          ...responseForCache.captions.playerCaptionsTracklistRenderer,
          captionTracks: deepClone(responseForCache.captions.playerCaptionsTracklistRenderer.captionTracks),
        },
      };
      responseForCache.captions.playerCaptionsTracklistRenderer.captionTracks.forEach((it: any) => {
        if (!it.baseUrl) return;
        it.baseUrl = decryptUrlSigAndN(it.baseUrl);
        if (poToken && !it.baseUrl.includes('pot=')) {
          it.baseUrl = it.baseUrl + '&pot=' + poToken;
        }
      });
    }
    wvApi.cachePlayerResponse(responseForCache);
    delete playerJson.adPlacements;
    delete playerJson.adSlots;
    delete playerJson.playerAds;

    // hide channel logo bottom-right
    playerJson.annotations = [];
    // disable endScreen
    delete playerJson.endscreen;
    // delete playerJson.adBreakHeartbeatParams;
    //
    // // if (config.audioOnly) {
    // //     playerJson.streamingData.adaptiveFormats = playerJson.streamingData.adaptiveFormats.filter(it => it.mimeType.includes('audio'));
    // //     playerJson.streamingData.formats = [];
    // // }
    // disable sabr
    if (playerJson.playerConfig?.mediaCommonConfig?.useServerDrivenAbr) {
      console.info('Detect SABR enabled');
    }
    if (playerJson.playerConfig?.daiConfig) {
      playerJson.playerConfig.daiConfig.sendSsdaiMissingAdBreakReasons = false;
    }

    // filter audio streams, only retains the highest quality for each mime type
    const adaptiveFormats = playerJson.streamingData?.adaptiveFormats;
    const isLive = playerJson.videoDetails?.isLive || playerJson.videoDetails?.isLiveContent;
    if (adaptiveFormats) {
      if (!adaptiveFormats.some((it: any) => it.url == null && !it.signatureCipher?.includes('url='))) {
        objPut(playerJson, 'playerConfig.mediaCommonConfig.useServerDrivenAbr', false);
        if (isLive) {
          playerJson.streamingData.serverAbrStreamingUrl_ = playerJson.streamingData.serverAbrStreamingUrl;
          delete playerJson.streamingData.serverAbrStreamingUrl;
        }
      } else {
        console.info('Detect SABR forced');
      }

      const audioHighestQuality: Record<string, number> = {};
      const createAudioKeyOrNull = (format: any) => {
        if (typeof format.mimeType == 'string' && format.mimeType?.includes('audio/')) {
          const mimeType = format.mimeType;
          const language = format.audioTrack?.id;
          const isDrc = format.isDrc;
          return `${mimeType}_${language}_${isDrc}`;
        }
      };
      for (let i = 0; i < adaptiveFormats.length; i++) {
        const format = adaptiveFormats[i];
        const audioKey = createAudioKeyOrNull(format);
        if (audioKey) {
          const bitrate = format.bitrate;
          audioHighestQuality[audioKey] = Math.max(audioHighestQuality[audioKey] || bitrate, bitrate);
          // console.log(audioKey, bitrate);
        }
      }
      const filteredAdaptiveFormats = [];
      for (let i = 0; i < adaptiveFormats.length; i++) {
        const format = adaptiveFormats[i];
        const audioKey = createAudioKeyOrNull(format);
        if (audioKey) {
          if (audioHighestQuality[audioKey] == format.bitrate) {
            filteredAdaptiveFormats.push(format);
          }
        } else {
          filteredAdaptiveFormats.push(format);
        }
      }
      // console.log(adaptiveFormats);
      // console.log(filteredAdaptiveFormats);
      playerJson.streamingData.adaptiveFormats = filteredAdaptiveFormats;
    }
    return playerJson;
  }

  function transformPlayerRequest(requestJson: any) {
    // if (navigator.userAgent?.includes('iPhone')) {
    //   requestJson.context.client.clientName = 'IOS';
    //   requestJson.context.client.clientVersion = '19.29.1';
    //   requestJson.context.client.deviceModel = 'iPhone16,2';
    // } else {
    //   objPut(requestJson, 'context.thirdParty.embedUrl', 'https://www.youtube.com/watch?v=');
    // }
    requestJson.racyCheckOk = true;
    requestJson.contentCheckOk = true;

    // objPut(requestJson, 'playbackContext.adPlaybackContext.pyv', true);
    // objPut(requestJson, 'playbackContext.adPlaybackContext.adType', 'AD_TYPE_INSTREAM');
    objPut(requestJson, 'playbackContext.contentPlaybackContext.isInlinePlaybackNoAd', true);
    const originPoToken = requestJson.serviceIntegrityDimensions?.poToken;
    let poToken = originPoToken;
    if ((!originPoToken || originPoToken.length <= 20) && (requestJson.videoId?.length == 11)) {
      const newToken = window.generatePoTokenForVideoSync(requestJson.videoId);
      if (newToken) {
        poToken = newToken;
        objPut(requestJson, 'serviceIntegrityDimensions.poToken', newToken);
      }
    }
    postPoToken(poToken, 'player_req');
    delete requestJson.serializedThirdPartyEmbedConfig;
    window.handleYtInternalLog?.('info', 'ymix', `player request ${JSON.stringify(requestJson)}`);
    return requestJson;
  }

  const poTokenTimestamp: Record<string, number> = {};
  function postPoToken(poToken?: string, from?: string) {
    if (poToken && poToken.length > 20) {
      let time = poTokenTimestamp[poToken];
      if (!time) {
        time = Date.now();
        poTokenTimestamp[poToken] = time;
        window.handleYtInternalLog?.('info', 'ymix', `po token from ${from}: ${poToken}`);
      }
      wvApi.postMsg(`${ymusicSessionId}_po_token`, {
        poToken: poToken,
        time: time,
      });
    } else {
      window.handleYtInternalLog?.('info', 'ymix', `po token from ${from} invalid: ${poToken}`);
    }
  }

  function b64ToU8(b64: string): Uint8Array {
    const bin = atob(b64);
    const u8 = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    return u8;
  }

  // darwin (iOS/macOS) player-cache delivery: WKWebView can't intercept https, so serve a cacheable
  // /videoplayback request over the WKScriptMessageHandlerWithReply channel the native AppleProxyChannel
  // installs. `open` returns {requestId,status,headers}; a ReadableStream then pulls base64 chunks
  // (`read` → chunk, or '' at EOF) into a Response. Returns null if the channel isn't present (→ network).
  async function serveViaProxyChannel(request: Request): Promise<Response | null> {
    const handlers = (window as any).webkit?.messageHandlers;
    const channel = handlers?.ymusicProxy;
    if (!channel) return null;
    let body: string | null = null;
    if ((request.method || 'GET') === 'POST') {
      body = u8ToBase64(new Uint8Array(await request.arrayBuffer()), /* urlSafe = */ false);
      if (body.startsWith('data:')) body = body.split(',')[1];
    }
    const metaStr = await channel.postMessage(
      JSON.stringify({ op: 'open', url: request.url, method: request.method || 'GET', body }),
    );
    const meta = JSON.parse(metaStr);
    const requestId = meta.requestId;
    const stream = new ReadableStream({
      async pull(controller) {
        const b64 = await channel.postMessage(JSON.stringify({ op: 'read', requestId }));
        if (b64 === '') controller.close();
        else controller.enqueue(b64ToU8(b64));
      },
      cancel() {
        try {
          channel.postMessage(JSON.stringify({ op: 'cancel', requestId }));
        } catch (e) { /* nothing to clean up */ }
      },
    });
    const headers = new Headers();
    for (const [k, v] of (meta.headers || [])) {
      try {
        headers.set(k, v);
      } catch (e) { /* skip a malformed header */ }
    }
    return new Response(stream, { status: meta.status || 200, headers });
  }

  function extractPoTokenFromPlaybackUrl(url?: string) {
    if (url && url?.indexOf('/videoplayback') > -1) {
      const exec = /[?&]pot=([^&#]+)(?:&|$|#)/.exec(url);
      if (exec) {
        postPoToken(exec[1], 'video_url');
      }
    }
  }

  window.XMLHttpRequest = proxyConstructor(window.XMLHttpRequest, {
    construct(target: any, argArray: any[]): object {
      const xhr = new target(...argArray);
      let transformedResponse = false;

      let url: string | undefined;

      // let method: string | undefined;

      function transformXhrResponse() {
        if (transformedResponse || xhr.readyState !== XMLHttpRequest.DONE) return;
        transformedResponse = true;

        if (xhr.status < 200 || xhr.status >= 300) {
          window.handleYtInternalLog?.('info', 'ymix', `xhr failure ${xhr.status} ${url}`);
        }

        if (isPlayerApiUrl(url as string)) {
          if (DEBUG) console.log(xhr.responseType, 'xhr.responseType');
          let bodyJson = xhr.responseType === 'json' ? xhr.response : JSON.parse(xhr.responseText);
          bodyJson = transformPlayerResponse(bodyJson);
          Object.defineProperty(xhr, 'response', {
            value: xhr.responseType === 'json' ? bodyJson : JSON.stringify(bodyJson),
            configurable: true,
          });
        }
      }

      xhr.addEventListener('loadend', transformXhrResponse);
      xhr.addEventListener('readystatechange', transformXhrResponse);

      const _open = xhr.open;
      const _send = xhr.send;
      xhr.open = function (...args: any[]) {
        // method = args[0];
        url = args[1].toString();
        extractPoTokenFromPlaybackUrl(url);
        if (url && isTimedTextApiUrl(url) && !url.includes('pot=') && url.includes('?')) {
          const urlParts = url.split('?', 2);
          const params = new URLSearchParams('?' + urlParts[1]);
          const videoId = params.get('v');
          if (videoId && videoId.length == 11) {
            const poToken = window.generatePoTokenForVideoSync(videoId);
            if (poToken) {
              url = url + '&pot=' + makeBase64UrlSafe(poToken);
              args[1] = url;
            }
          }
        }
        return _open.apply(this, args as any);
      };
      xhr.send = function (...args: any[]) {
        if (isPlayerApiUrl(url as any)) {
          let body = JSON.parse(args[0]);
          body = transformPlayerRequest(body);
          args[0] = JSON.stringify(body);
        }
        return _send.apply(this, args as any);
      };

      return xhr;
    },
  }) as any;

  window.fetch = proxyMethodPolyfill(window.fetch, {
    apply: async (target: (input: (RequestInfo | URL), init?: RequestInit) => Promise<Response>, thisArg: any, argArray: any[]): Promise<any> => {
      let request = argArray[0];
      if (!(request instanceof Request)) {
        request = new Request(request, argArray[1]);
      }
      const isPlayerApi = isPlayerApiUrl(request.url);
      if (isPlayerApi) {
        const newBody = transformPlayerRequest(await request.json());
        request = new Request(request, { body: JSON.stringify(newBody) });
      }
      const isVideoPlayback = request.url.includes('/videoplayback');
      const isSabr = request.url.includes('sabr=1');
      const isProbe = request.url.includes('probe=1');
      const isLive = request.url.includes('live=1') || request.url.includes('yt_live_broadcast') || request.url.includes('noclen=1');
      if (isVideoPlayback) {
        extractPoTokenFromPlaybackUrl(request.url);
        if (isSabr && !isProbe && !isLive) {
          const clonedRequest = request.clone();
          const body = new Uint8Array(await clonedRequest.arrayBuffer());
          if (body) {
            try {
              processSabrRequestBody(wvApi, request.url, body, (newBody) => {
                request = new Request(request, { body: newBody.buffer });
              }, token => postPoToken(token, 'sabr'));
            } catch (error: any) {
              wvApi.reportError('handle sabr request body failure: ' + error?.message, error?.stack);
            }
          }
        }
      }

      const hasClen = request.url.includes('clen=');
      // Player-cache delivery, by platform. Android (WebViewClient, osVer≥24) and desktop (JCEF) can
      // intercept the rewritten /ymusicForward natively, so there we rewrite the request URL. iOS/macOS
      // (darwin) can't intercept https, so there we serve the whole response over the WKWebView message
      // channel (WKScriptMessageHandlerWithReply) and return it directly.
      const cachePlatform = window.ymixInitConfig?.platform;
      const cachePlatformOk = (window.isAndroid && window.androidOsVer && window.androidOsVer >= 24)
        || cachePlatform === 'jvm' || cachePlatform === 'darwin';
      const enablePlayerCache = window.enablePlayerCache && cachePlatformOk;
      const canCache = isVideoPlayback && (!isSabr || canCacheSabr) && !isLive && (hasClen || isSabr) && !isProbe;
      if (enablePlayerCache && canCache) {
        if (cachePlatform === 'darwin') {
          try {
            const cached = await serveViaProxyChannel(request);
            if (cached) return cached;
          } catch (error: any) {
            wvApi.reportError('proxy channel failed: ' + error?.message, error?.stack);
          }
          // channel absent / failed → fall through to the network fetch below
        } else {
          const params = new URLSearchParams();
          params.set('url', request.url);
          params.set('method', request.method || 'GET');
          if (request.method === 'POST') {
            // must be normal base64 (urlSafe=false) otherwise, will crash ymusic
            let bodyBase64 = u8ToBase64(new Uint8Array(await request.arrayBuffer()), /* urlSafe = */false);
            if (bodyBase64.startsWith('data:')) {
              bodyBase64 = bodyBase64.split(',')[1];
            }
            params.set('body', bodyBase64);
          }
          const url = window.location.origin + '/ymusicForward?' + params.toString();
          request = new Request(url, {
            method: 'GET',
            headers: request.headers,
          });
        }
      }

      return target.apply(thisArg, [request])
        .then(async (res) => {
          if (res.status < 200 || res.status >= 300) {
            window.handleYtInternalLog?.('info', 'ymix', `Detect fetch failure ${res.status} ${res.url}`);
          }
          if (!reported403 && isVideoPlayback && res.status == 403 && window.debugVideo403) {
            reported403 = true;
            wvApi.remoteDebug('wv_video_403_3', window.ymusicPlayerReport?.());
          }
          if (isPlayerApi) {
            const newBody = transformPlayerResponse(await res.json());
            res = new Response(new Blob([JSON.stringify(newBody)], { type: 'application/json' }), {
              status: res.status,
              statusText: res.statusText,
              headers: res.headers,
            });
          }
          return res;
        })
        .catch((error) => {
          if (error && error.message && error.message.includes('Failed to fetch')) {
            window.handleYtInternalLog?.('info', 'ymix', `fetch failure ${error.message} ${request.url}`);
          }
          throw error;
        });
    },
  });

  window.setTimeout = proxyMethodPolyfill(window.setTimeout, {
    apply(target: (handler: TimerHandler, timeout?: number, ...args: any[]) => number, thisArg: any, argArray: any[]): any {
      const [, delayMs] = argArray;
      if (delayMs === 17000) {
        argArray[1] = 0.1;
      }
      return target.apply(thisArg, argArray as any);
    },
  });

  // let moviePlayer: any;
  // const checkAds = () => {
  //   moviePlayer = moviePlayer || document.getElementById('movie_player');
  //   if (!moviePlayer?.getStatsForNerds?.()?.debug_info?.startsWith?.('SSAP, AD')) return;
  //   const progressState = moviePlayer.getProgressState?.();
  //   if (progressState && progressState.duration > 0 && progressState.loaded < progressState.duration) {
  //     moviePlayer.seekTo?.(progressState.duration);
  //   }
  // };

  // new MutationObserver(checkAds).observe(document, { childList: !0, subtree: !0 });
}
