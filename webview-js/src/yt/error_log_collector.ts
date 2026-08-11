import { regexReplace, regexReplaceFlow } from './utils';

export function errorLogCollector(wvApi = window.ymixWvApi) {
  window.addEventListener('error', (event) => {
    if (event) {
      const { message, filename, lineno, colno, error } = event;
      window.handleYtInternalLog?.('error', 'w_error', [
        `msg: ${message}`,
        `filename: ${filename}`,
        `lineno: ${lineno}`,
        `colno: ${colno}`,
        `error: ${error?.stack}`,
      ].join('\n'));
    }
  });
  window.addEventListener('unhandledrejection', (event) => {
    window.handleYtInternalLog?.('error', 'w_unhandled_error', event?.reason?.stack);
  });
  wvApi.registerScriptTransformer((script: string, url?: string) => {
    if (url?.includes('/base.js')) {
      script = regexReplaceFlow('error_log_collector', url, script, () => [
        regexReplace(script, /[,;]\s*(\w+)\s*=\s*{\s*errorCode:[\s\S]+?}/g, ([match, varName]) => {
          return `${match}, window.handleYtInternalLog && window.handleYtInternalLog('error', 'yt_js_error1', JSON.stringify(${varName}))`;
        }),
        regexReplace(script, /(\w+)\.hasOwnProperty\("level"\)/, ([match, varName]) => {
          return `window.handleYtInternalLog && window.handleYtInternalLog('error', 'yt_js_error2', JSON.stringify(${varName})), ${match}`;
        }),
      ]);
    }
    return script;
  });
}
