import { CONST } from '../constants';
import { regexReplace, regexReplaceFlow } from './utils';
import './bot_guards';

export function fakeClient(wvApi = window.ymixWvApi) {
  if (location.href.toString().includes('/embed')) {
    const fakeVid = '12345678901';
    history.replaceState({}, '', `/watch?v=${fakeVid}`);
    wvApi.registerScriptTransformer((script: string, url?: string) => {
      if (url == null && script.includes('WEB_EMBEDDED_PLAYER')) {
        script = script.replace(
          /"(INNERTUBE_CLIENT_NAME|clientName|client.name|interfaceName)"\s*:\s*"WEB_EMBEDDED_PLAYER"/g,
          '"$1": "WEB"',
        );
        script = script.replace(
          /"(INNERTUBE_CLIENT_VERSION|clientVersion|innertubeContextClientVersion|interfaceVersion|INNERTUBE_CONTEXT_CLIENT_VERSION)"\s*:\s*"[^"]+"/g,
          `"$1": "${CONST.FAKE_CLIENT_VER}"`,
        );
        script = script.replace(
          /"(INNERTUBE_CONTEXT_CLIENT_NAME)"\s*:\s*\d+/g,
          '"$1": 1',
        );
        script = script.replace(
          /"(isEmbed)"\s*:\s*true/g,
          '"$1": false',
        );
        script = script.replace(
          /"(embedsEnableEmc3ds)"\s*:\s*true/g,
          '"$1": false',
        );
        script = script.replace(
          /"(eventLabel)"\s*:\s*"[^"]+"/,
          '"$1": "detailpage"',
        );
        // if (!script.includes('"ID_TOKEN"')) {
        //   // inject ID_TOKEN
        //   script = script.replace(
        //     /("INNERTUBE_CLIENT_VERSION"\s*:)/,
        //     '"ID_TOKEN": window._idToken, $1',
        //   );
        // }
        // "originalUrl": "https://www.youtube.com/embed",
        script = script.replace(
          /"originalUrl"\s*:\s*"https:\/\/www.youtube.com\/embed[^"]*"/,
          `"originalUrl": "https://www.youtube.com/watch?v=${fakeVid}"`,
        );
      } else if (url?.includes('www-embed-player') || (url?.includes('/ytembeds/') && url?.includes('ytembeds.base'))) {
        script = regexReplaceFlow('embed_player', url, script, () => [
          regexReplace(script, /\w+(\.\w+)*\([^()]*?"enable_rta_manager"[^)]*?\)/g, () => 'true'),
          regexReplace(script, /\w+(\.\w+)*\([^()]*?"attmusi[^"]*"[^)]*?\)/g, ([match]) => ((DEBUG ? `/*${match}*/ ` : '') + 'true')),
          regexReplace(script, /=\w+\.getReelItemWatchResponse\(\)/g, () => '=null'),
          regexReplace(script, /=\w+\.getResolveUrlResponse\(\)/g, () => '=null'),
          regexReplace(script, /if\s*\((?:!\((\w+) instanceof Function\)|typeof (\w+)\s*!==\s*"function")\)\s*throw new \w+\([^")]+"APF/, ([match, group1, group2]) => {
            const varName = group1 || group2;
            return `window.onPoTokenMinterAvailable && (${varName} = window.onPoTokenMinterAvailable(${varName})); ${match}`;
          }),
        ]);
      }
      return script;
    });
  }
}
