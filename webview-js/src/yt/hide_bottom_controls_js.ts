import { regexReplace, regexReplaceFlow } from './utils';

export function hideChromeBottom(wvApi = window.ymixWvApi, isDesktop?: boolean) {
  if (isDesktop) return;
  wvApi.registerScriptTransformer((script: string, url?: string) => {
    if (url?.includes('/base.js')) {
      script = regexReplaceFlow('h_chrome_bottom', url, script, () => [
        regexReplace(script, /(this\.\w+)\s*=\s*this\.api\.[^.]+\.controlsType\s*===\s*"1"[^;]+?;/, ([, group1]) => {
          return `${group1}=null;`;
        }),
      ]);
    }
    return script;
  });
}
