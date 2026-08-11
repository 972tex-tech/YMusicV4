import { regexReplace, regexReplaceFlow } from './utils';

export function disableClickTogglePlay(wvApi = window.ymixWvApi) {
  wvApi.registerScriptTransformer((script: string, url?: string) => {
    if (url?.includes('/base.js')) {
      script = regexReplaceFlow('disableClickTogglePlay', url, script, () => [
        regexReplace(script, /this\.\w+\(\w+,\s*"click",\s*this\.\w+,\s*this\);\s*(this\.\w+\(\w+,\s*"dblclick",\s*this\.\w+,\s*this\);\s*this\.\w+\(\w+,\s*"mousedown",\s*this\.\w+,\s*this\);)/, ([match, doubleClickLine]) => {
          return `/*${match}*/ ${doubleClickLine}`;
        }),
      ]);
    }
    return script;
  });
}
