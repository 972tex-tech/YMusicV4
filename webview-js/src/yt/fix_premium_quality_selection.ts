import { regexReplace, regexReplaceFlow } from './utils';

export function fixPremiumQualitySelection(wvApi = window.ymixWvApi) {
  if (location.href.toString().includes('/embed')) {
    wvApi.registerScriptTransformer((script: string, url?: string) => {
      if (url?.includes('/base.js')) {
        script = regexReplaceFlow('fix_1080p', url, script, () => [
          regexReplace(script, /\w+(\.\w+)*\([^()]*?"web_player_enable_premium_hbr_(in_h5_api|playback_cap)"[^)]*?\)/g, () => {
            return 'true';
          }),
        ]);
      }
      return script;
    });
  }
}
