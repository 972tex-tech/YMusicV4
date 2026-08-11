interface Window {
  ymixInitConfig: YmixInitConfig;
  ymixWvApi: import('./webview_api').YMixWebViewApi;
  ymixChannelAndroid?: {
    postMessage(channel: string, msg: string, isMainFrame: boolean, origin?: string): void;
    remoteDebug(scope: string, payload: string): void;
  };
  webkit?: {
    messageHandlers?: {
      ymixChanelIos: {
        postMessage: (msg: string) => void;
      };
    };
  };
  trustedTypes?: {
    createPolicy: (name: string, obj: TrustedTypes) => TrustedTypes;
  };

  isAndroid?: boolean;
  androidOsVer?: number;
  enablePlayerCache?: boolean;
  mainAppVer?: number;
  saveYtLogRecords?: boolean;
  maxYtLogRecords?: number;

  // debug flags
  sendPlayerLogRecords?: boolean;
  debugPlayerFailure?: boolean;
  debugVideo403?: boolean;
  debugUnplayable?: boolean;
  customUnplayableError?: boolean;
}

interface YmixInitConfig {
  platform: 'darwin' | 'android' | 'jvm';
}

interface TrustedTypes {
  createScript: (s: string) => string;
  createScriptURL: (s: string) => string;
  createHTML: (s: string) => string;
}

type ScriptTransformer = (script: string, url?: string, originUrl?: string) => string;

declare const DEBUG: boolean;
declare const FORCE_RE_MISMATCH_CRASH: boolean;
declare const BUILD_DATE: number;
declare const ymusicSessionId: string;
