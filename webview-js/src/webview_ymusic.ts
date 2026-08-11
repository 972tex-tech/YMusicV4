import { YMixWebViewApi } from './webview_api';
import { hookScriptLoad } from './wv/intercept_script';
import { ytScripts } from './yt_script';

(function () {
  const isMainFrame = window.self == window.top;
  if (!isMainFrame) return;
  if (!window.queueMicrotask) {
    window.queueMicrotask = function (cb) {
      return Promise.resolve().then(cb);
    };
  }

  const wvApi = new YMixWebViewApi();
  Object.defineProperty(window, 'ymixWvApi', { value: wvApi, configurable: true });

  hookScriptLoad(wvApi);
  ytScripts(wvApi);
})();
