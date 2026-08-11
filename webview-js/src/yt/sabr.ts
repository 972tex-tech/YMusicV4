import { regexReplace, regexReplaceFlow } from './utils';

export function tryDisableSabr(wvApi = window.ymixWvApi) {
  wvApi.registerScriptTransformer((script: string, url?: string) => {
    if (url?.includes('/base.js')) {
      // disable use UMP, it's easier for caching video data
      script = regexReplaceFlow('use_ump', url, script, () => [
        regexReplace(script, /this.useUmp=[^;]+;/, () => {
          return 'this.useUmp=false;';
        }),

        // DEBUG LOGS - unfortunately this no longer available
        // regexReplace(script, /prototype.debug=function\(\)\{};/, () => 'prototype.debug=function (arg) { window.handleYtInternalLog && window.handleYtInternalLog("debug", this.tag, arg); };'),
        // regexReplace(script, /prototype.info=function\(\)\{};/, () => 'prototype.info=function (arg) { window.handleYtInternalLog && window.handleYtInternalLog("info", this.tag, arg); };'),

        // regexReplace(script, /switch\s*\((\w+)\)\s*\{\s*case 20:\s*(\w+)\s*=\s*new\s+\w+\((\w+)\);.+?break;/, ([match, v1, v2, v3]) => {
        //   return `console.log("Part ID=" + ${v1} + " len=" + ${v3}.totalLength);` + match.replace('break;', `console.log("Media header", ${v2}); break;`);
        // }),
        // regexReplace(script, /(\w+)\s*=\s*\w+\.getUint8\(0\);\s*if\s*\((\w+).getLength\(\)\s*!==\s*1\)\s*{/, ([match, group1, group2]) => {
        //   return match + `console.log("Media content headerId=" + ${group1} + " len=" + ${group2}.getLength());`;
        // }),
        // regexReplace(script, /return\s*{\s*targetAudioReadaheadMs:[^}]+}/, ([match, group1, group2]) => {
        //   return match.replace('return', 'var xxx = ') + '; console.log("Next request policy", xxx); return xxx;';
        // }),
        // DEBUG LOGS
        regexReplace(script, /13\s*,\s*([\w$]+\.timeSinceLastManualFormatSelectionMs)/, ([match, group1]) => {
          return match.replace(group1, `Math.max(0, ${group1})`);
        }),
        regexReplace(script, /\w+(\.\w+)*\([^()]*?"(html5_remove_client_sabr_determination|html5_set_video_id_as_expected_content_binding|use_rta_for_player|attmusi[^"]*|html5_use_shared_owl_instance)"[^)]*?\)/g, ([match]) => {
          return (DEBUG ? `/*${match}*/ ` : '') + 'true';
        }),
        regexReplace(script, /this\.isAudioOnly=[^;]+;/, () => {
          return 'this.isAudioOnly = false;';
        }),
      ]);
    }
    return script;
  });
}
