import { CONST } from './constants';
import './debug_utils';

const MINUTE_IN_MS = 60 * 1000;
const PLAYER_RES_CACHE_DUR = 3 * MINUTE_IN_MS;

export class YMixWebViewApi {
  readonly postMsg: (channel: string, msg: any) => void;
  readonly reportError: (message: string, extras?: string) => void;
  readonly remoteDebug: (scope: string, payload: string) => void;
  readonly trustedTypes: TrustedTypes;
  readonly registerScriptTransformer: (transformer: ScriptTransformer) => void;
  readonly transformScript: ScriptTransformer;
  private readonly wantedPlayerRes = new Set<string>();
  private readonly cachedPlayerRes = new Map<string, { playerRes: any; expires: number }>();
  private scriptTransformers: ScriptTransformer[] = [];
  private baseJsUrl?: string;

  constructor() {
    const isMainFrame = window.self == window.top;
    const isDesktop = navigator.userAgent.includes('Macintosh');
    this.postMsg = (channel, msg) => {
      if (window.ymixChannelAndroid) {
        window.ymixChannelAndroid.postMessage(
          channel, typeof msg == 'string' ? msg : JSON.stringify(msg),
          isMainFrame, window.origin,
        );
      } else if (window.webkit?.messageHandlers?.ymixChanelIos) {
        const msgFormatted = typeof msg == 'string' ? msg : JSON.stringify(msg);
        window.webkit.messageHandlers.ymixChanelIos.postMessage(channel + '|' + msgFormatted);
      } else if (DEBUG && isDesktop) {
        if (channel.includes('_progress')) {
          // console.info('postMsg', channel);
        } else {
          console.log('postMsg', channel, msg);
        }
      } else if (DEBUG) {
        console.log('Missing native message channel', channel, msg);
        return;
      }
    };
    this.reportError = (message: string, extras?: string) => {
      this.postMsg('report_error', {
        message: `[${BUILD_DATE}] ${message}`, extras: `${this.baseJsUrl}\n${extras}`,
      });
      if (DEBUG) {
        console.log('report_error', message, extras);
      }
    };
    this.remoteDebug = (scope: string, payload: string) => {
      if (window.ymixChannelAndroid?.remoteDebug) {
        window.ymixChannelAndroid.remoteDebug(scope, payload);
      }
    };
    const trustedTypes: TrustedTypes = {
      createScript: s => s,
      createScriptURL: s => s,
      createHTML: s => s,
    };
    if (window.trustedTypes) {
      try {
        eval('console.log(\'eval passed\')');
        this.trustedTypes = trustedTypes;
      } catch (ignore) {
        this.trustedTypes = window.trustedTypes.createPolicy('ymix', trustedTypes);
      }
    } else {
      this.trustedTypes = trustedTypes;
    }

    this.registerScriptTransformer = (transformer: ScriptTransformer) => this.scriptTransformers.push(transformer);
    let inlineCount = 0;
    this.transformScript = (script, url, originUrl) => {
      if (url && url.indexOf('/base.js') > -1) {
        this.baseJsUrl = url;
      }
      let res = script;
      const label = `trans-${url ?? 'inline-' + (inlineCount + 1)}`;
      if (DEBUG) console.time(label);
      for (let i = 0; i < this.scriptTransformers.length; i++) {
        const transformer = this.scriptTransformers[i];
        res = transformer(res, url);
      }
      if (DEBUG) {
        res = `${originUrl ? `//# originURL=${originUrl}\n` : ''}//# sourceURL=${url || 'inline-' + (++inlineCount)}\n` + res;
        console.timeEnd(label);
      }
      return this.trustedTypes.createScript(res);
    };

    setInterval(() => {
      [...this.cachedPlayerRes.keys()].forEach((key) => {
        if ((this.cachedPlayerRes.get(key)?.expires ?? 0) < Date.now()) {
          this.cachedPlayerRes.delete(key);
        }
      });
    }, PLAYER_RES_CACHE_DUR);
  }

  readonly getBaseJsUrl = (): string | undefined => {
    return this.baseJsUrl;
  };

  private readonly getCurrentPlayerRes = () => {
    return (document.getElementById('movie_player') as any)?.getPlayerResponse();
  };

  private readonly postPlayerRes = (playerRes?: any) => {
    const videoId = playerRes?.videoDetails?.videoId;
    if (videoId && this.wantedPlayerRes.has(videoId)) {
      const streamingData = playerRes.streamingData;
      const data = {
        clientName: 'WEB',
        clientNameId: 1,
        clientVersion: CONST.FAKE_CLIENT_VER,
        playerJs: this.baseJsUrl,
        videoDetails: playerRes.videoDetails,
        microformat: playerRes.microformat,
        playabilityStatus: playerRes.playabilityStatus,
        streamingData: streamingData,
        captions: playerRes.captions,
        // Flattened caption list for the download path: baseUrls already have signature/n decoded + a
        // bound pot (see skip_ads transformPlayerResponse), so the native side can GET them directly.
        // The raw `captions` above is kept for backward compatibility.
        captionTracks: (playerRes.captions?.playerCaptionsTracklistRenderer?.captionTracks ?? []).map((t: any) => ({
          languageCode: t.languageCode,
          name: t.name?.simpleText ?? t.name?.runs?.[0]?.text ?? t.languageCode,
          baseUrl: t.baseUrl,
          isAsr: t.kind === 'asr',
          isDefault: (t.vssId ?? '').startsWith('.'),
        })),
        videoPlaybackUstreamerConfig: playerRes.playerConfig?.mediaCommonConfig?.mediaUstreamerRequestConfig?.videoPlaybackUstreamerConfig,
      };
      this.postMsg(`${ymusicSessionId}_player_res`, {
        videoId: videoId,
        data: data,
      });
      this.wantedPlayerRes.delete(videoId);
    }
  };

  readonly cachePlayerResponse = (playerResponse: any) => {
    const videoId = playerResponse?.videoDetails?.videoId;
    if (videoId) {
      const isPlayOk = playerResponse?.playabilityStatus?.status == 'OK';
      if (isPlayOk) {
        this.cachedPlayerRes.set(videoId, { playerRes: playerResponse, expires: Date.now() + PLAYER_RES_CACHE_DUR });
      }
      if (this.wantedPlayerRes.has(videoId)) {
        this.postPlayerRes(playerResponse);
      }
    }
  };

  readonly addWantedPlayerRes = (videoId: string) => {
    this.wantedPlayerRes.add(videoId);
    const cachedPlayerRes = this.cachedPlayerRes.get(videoId);
    if (cachedPlayerRes != null) {
      cachedPlayerRes.expires = Date.now() + PLAYER_RES_CACHE_DUR;
      this.postPlayerRes(cachedPlayerRes.playerRes);
    } else {
      const playerRes = this.getCurrentPlayerRes();
      if (playerRes?.videoDetails?.videoId == videoId) {
        this.postPlayerRes(playerRes);
      }
    }
  };

  readonly findPlayerResponse = (predicate: (videoId: string, playerRes: any) => boolean): any => {
    const keys = [...this.cachedPlayerRes.keys()];
    for (let i = 0; i < keys.length; i++) {
      const videoId = keys[i];
      const data = this.cachedPlayerRes.get(videoId);
      if (data && predicate(videoId, data.playerRes)) {
        data.expires = Date.now() + PLAYER_RES_CACHE_DUR;
        return data.playerRes;
      }
    }

    const playerRes = this.getCurrentPlayerRes();
    if (playerRes?.videoDetails?.videoId && predicate(playerRes.videoDetails.videoId, playerRes)) {
      return playerRes;
    }
  };
}
