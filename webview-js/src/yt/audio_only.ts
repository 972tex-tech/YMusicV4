import { fixReVarName, regexReplace, regexReplaceFlow } from './utils';

export function audioOnly(wvApi = window.ymixWvApi) {
  (window as any).magicFixAudioOnlyVideoTrack = function (selectedVideo: any, dashLoader: any) {
    const moviePlayer: any = document.getElementById('movie_player');
    if (!moviePlayer) return selectedVideo;
    const isAudioOnlyRequested = moviePlayer.getUserPlaybackQualityPreference() === 'zero';
    if (!isAudioOnlyRequested && selectedVideo.mimeType === 'fakesb') {
      const dashAbrProps = Object.keys(dashLoader);
      let sabrLoader;
      for (let iii = 0; iii < dashAbrProps.length; iii++) {
        if (dashLoader[dashAbrProps[iii]] && dashLoader[dashAbrProps[iii]].selectAudioOnlyTrack) {
          sabrLoader = dashLoader[dashAbrProps[iii]];
          break;
        }
      }
      if (sabrLoader) {
        return sabrLoader.videoInfos[0];
      }
    }
    return selectedVideo;
  };

  wvApi.registerScriptTransformer((script: string, url?: string) => {
    if (url?.includes('/base.js')) {
      const appendFakeSbFunctionName = (fixReVarName(/(\w+)\s*=\s*function\s*\(\w+,\s*\w+\)\s*{\s*if\s*\(!\w+\.\w+\["0"]/).exec(script) ?? [])[1];
      if (FORCE_RE_MISMATCH_CRASH) {
        if (!appendFakeSbFunctionName) {
          throw new Error('appendFakeSbFunctionName == null');
        }
      } else {
        console.info(`appendFakeSbFunctionName = ${appendFakeSbFunctionName}`);
      }
      script = regexReplaceFlow('audio_only', url, script, () => [
        regexReplace(script, /(\w+)\.auto\s*=\s*0\s*,\s*\w+\.tiny\s*=/, ([match, varName]) => {
          return `${varName}.zero = 1,${match}`;
        }),
        regexReplace(script, /0\s*:\s*"auto"\s*,\s*144\s*:\s*"tiny"\s*,/, ([match]) => {
          return `${match} 1: "zero",`;
        }),
        regexReplace(script, /large medium small tiny/, ([match]) => {
          return `${match} zero`;
        }),
        regexReplace(script, /0\s*,\s*0\s*,\s*0\s*,\s*void 0\s*,\s*void 0\s*,\s*"auto"/, ([match]) => {
          return match.replace('"auto"', '"zero", "Audio only"');
        }),
        regexReplace(script, /(return\s*\["f"\]);[\s\S]+?return \w+;?\s*}\s*;/, ([match, theReturn]) => {
          return match.replace(theReturn, '1')
            .replace('"9h 9 h 8 (h ( H *"', '"9h 9 h 8 (h ( H * f"')
            .replace('"9h 9 h 8 H (h ( *"', '"9h 9 h 8 H (h ( * f"')
            .replace('"9h","9","h","8"', '"9h","9","h","8","f"');
        }),
        regexReplace(script, /\w+\s*=\s*(\w+)\["9h"\]\s*\?\s*"9h"\s*:\s*"9";[\s\S]+?};/, ([funcBody, varName]) => {
          return funcBody.replace(fixReVarName(/return \w+\(new \w+\((\w+)(?:\s*,\s*\w+\s*)+\)\)/g), (match, arrayName) => {
            return `if (${varName}["f"] && ${varName}["f"].length && !${arrayName}.includes(${varName}["f"][0])) ${arrayName}.push(${varName}["f"][0]); ${match}`;
          });
        }),
        regexReplace(script, /this.controlsType\s*=\s*[^=][^;]+?;/g, () => {
          return `this.controlsType = "1";`;
        }),
        regexReplace(script, /var\s*[\w$]+\s*=\s*"unknown";\s*if\s*\(this\.videoData\.[\w$]/, ([match]) => {
          return match.replace('"unknown"', '"zero"');
        }),
        regexReplace(script, new RegExp('\\w+\\(\\w+\\)\\s*&&\\s*' + appendFakeSbFunctionName?.replace('$', '\\$') + '\\(', 'g'), () => {
          return 'true && ' + appendFakeSbFunctionName + '(';
        }),
        regexReplace(script, /function\s*\((\w+),\s*(\w+)\)\s*{\s*if\s*\((!\w+\.\w+\["0"])\)/, ([match, var1, var2, block]) => {
          const newBlock = block + ` && !${var2} && !${var1}.isLivePlayback`;
          return match.replace(block, newBlock);
        }),
        regexReplace(script, /this\.isAudioOnly\s*&&\s*([\s\S]+?)[;}]/, ([match, group1]) => {
          return `var _this = this; this.selectAudioOnlyTrack = function() { ${group1.replace(/this/g, '_this')} } ; ${match}`;
        }),
        regexReplace(script, /=\s*(\w+\.\w+)\s*>\s*(\w+\.\w+)\s*&&\s*\w+\.reason\s*===\s*"b"/g, ([match, v1, v2]) => {
          return `${match} || ${v1} == 1 || ${v2} == 1`;
        }),
        // The reattach decision (both the SABR/QZ path and the classic path): when the OR-chain of
        // constraints would tear the MediaSource down and rebuild it, suppress it across a
        // zero<->video switch so the player keeps one MediaSource and takes its in-place path.
        // __ymNoReattach() (blank_feeder) is true for both directions of the switch.
        //
        // Anchored on the STABLE object-key literal `reattachOnConstraint` (a semantic property name,
        // not an obfuscated identifier), with the callee expression a wildcard dotted chain
        // `(?:[\w$]+\.)+[\w$]+` — so it survives base.js renaming `this.ma.hu` to anything else.
        // Matches `w||Q||D||Y?<expr>({reattachOnConstraint…` and `Q||Y||w?(<expr>({reattachOnConstraint…`.
        regexReplace(script, /([\w$]+(?:\|\|[\w$]+)+)(\?\(?(?:[\w$]+\.)+[\w$]+\(\{reattachOnConstraint)/g, ([, chain, tail]) => {
          return `(${chain}) && !(window.__ymNoReattach && window.__ymNoReattach())${tail}`;
        }),
        // sourceopen: never skip the video SourceBuffer — map the fake format's mime to blank vp9 so
        // a real video SB always exists (one MediaSource for the whole session). Anchored on the
        // stable shape `<expr>.split(";")[0]==="fakesb"?void 0:` (the "fakesb" sentinel + the
        // skip-the-SB ternary); the assignment target and mime var are wildcards, no hardcoded names.
        regexReplace(script, /([\w$]+\.[\w$]+)\s*=\s*([\w$]+)\.split\(";"\)\[0\]\s*===\s*"fakesb"\s*\?\s*void 0\s*:/, ([match, _lhs, sVar]) => {
          return `${sVar} = window.__ymBlankMime ? window.__ymBlankMime(${sVar}) : ${sVar}; `
            + match.replace('"fakesb"', '"fakesb" && !window.__ymBlankMime');
        }),
        regexReplace(script, /(\w+\.\w+)\)\s*:\s*(\w+).videoInfos\s*=\s*\2\.\w+;/, ([match, videoSize, sabrLoaderVar]) => {
          return `${match} if (${videoSize} === 1) ${sabrLoaderVar}.selectAudioOnlyTrack();`;
        }),
        regexReplace(script, /(\w+)\s*=\s*\(\w+\s*\?\s*\w+\.info\.\w+\s*:\s*(\w+)\.videoTrack\.\w+\)\.info([,;])/, ([match, videoInfoVar, dashAbrVar, separator]) => {
          return match + `${videoInfoVar}=window.magicFixAudioOnlyVideoTrack(${videoInfoVar}, ${dashAbrVar})${separator}`;
        }),
        regexReplace(script, /"html5_performance_cap_floor"\);([\s\S]+?)return[\s\S]+?;?[\s\S]+?}/, ([match, block]) => {
          return match.replace(block, `/* ${block} */`);
        }),
      ]);
    }
    return script;
  });
}
