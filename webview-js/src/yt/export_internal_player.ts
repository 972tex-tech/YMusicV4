import { regexReplace, regexReplaceFlow } from './utils';

export function exportInternalPlayer(wvApi = window.ymixWvApi) {
  wvApi.registerScriptTransformer((script: string, url?: string) => {
    if (url?.includes('/base.js')) {
      script = regexReplaceFlow('internal_player', url, script, () => [
        regexReplace(script, /this\.app\s*=\s*\w+;\s*this.state\s*=\s*\w+/, ([match]) => {
          return `(window.ytInternalPlayer=this),${match}`;
        }),
      ]);
    }
    return script;
  });
}
