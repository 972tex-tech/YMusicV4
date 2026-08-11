import { fixReVarName, regexReplace, regexReplaceFlow } from './utils';

declare global {
  interface Window {
    ymixSigDe?: (url: string, s: string, sp: string) => any;
    ymixNDe?: (n: string) => string;
  }
}

export function mockSignatureDecoder(wvApi = window.ymixWvApi) {
  wvApi.registerScriptTransformer((script: string, url?: string) => {
    if (url?.includes('/base.js')) {
      // noinspection SpellCheckingInspection
      script = regexReplaceFlow('ndee', url, script, () => {
        const initStringArray = fixReVarName(/(\w+)=(\[[\s\S]+?"\]),\s*\w+,\s*\w+,\s*\w+,\s*\w+,\s*\w+,\s*\w+/).exec(script)
          || fixReVarName(/(\w+)=(["'].+?["']\.split\(["'].["']\))\s*,\s*\w+\s*,\s*\w+\s*,\s*\w+\s*,\s*\w+\s*/).exec(script);
        if (!initStringArray) {
          wvApi.reportError(`init array not found: ${url}`);
          return [];
        }
        let stringArrayValue: string[] | undefined;
        try {
          stringArrayValue = eval(initStringArray[2]);
        } catch (e) {
          wvApi.reportError(`eval init array failure: ${url} ${e}`);
          return [];
        }
        if (!Array.isArray(stringArrayValue)) {
          wvApi.reportError(`invalid init array: ${url}`);
          return [];
        }

        const nParamDecodeBlock = fixReVarName(/(?:var )?(\w+)\s*=([^=;()]+?\([^)]*,?\s*(\w+)\))[;,]\s*\w+(?:\.set|\[\w+\[(\d+|\w\s*\^\s*\d+)\]\])\((?:"n"|\w+\[(\d+|\w\s*\^\s*\d+)\],\s*\1)/).exec(script);
        if (!nParamDecodeBlock) {
          wvApi.reportError(`n block not found: ${url}`);
          return [];
        }

        {
          // validate n decode block
          if (nParamDecodeBlock[4]?.includes('^')) {
            const [xorParam, xorValue] = nParamDecodeBlock[4].split('^');
            const expectOriginalValue = stringArrayValue.indexOf('set');
            const xorParamValue = expectOriginalValue ^ parseInt(xorValue);
            if ((nParamDecodeBlock[5]?.includes('^') && stringArrayValue[parseInt(nParamDecodeBlock[5].split('^')[1]) ^ xorParamValue] != 'n')
              || (!nParamDecodeBlock[5]?.includes('^') && stringArrayValue[parseInt(nParamDecodeBlock[5])] != 'n')) {
              wvApi.reportError(`invalid n block: ${url} ${nParamDecodeBlock[0]}`);
              return [];
            }
            nParamDecodeBlock[2] = nParamDecodeBlock[2].replace(new RegExp(xorParam.trim() + '\\s*\\^', 'g'), xorParamValue + '^');
          } else if (nParamDecodeBlock[5]?.includes('^')) {
            const [xorParam, xorValue] = nParamDecodeBlock[5].split('^');
            const expectOriginalValue = stringArrayValue.indexOf('n');
            const xorParamValue = expectOriginalValue ^ parseInt(xorValue);
            if ((nParamDecodeBlock[4]?.includes('^') && stringArrayValue[parseInt(nParamDecodeBlock[4].split('^')[1]) ^ xorParamValue] != 'set')
              || (!nParamDecodeBlock[4]?.includes('^') && stringArrayValue[parseInt(nParamDecodeBlock[4])] != 'set')) {
              wvApi.reportError(`invalid n block: ${url} ${nParamDecodeBlock[0]}`);
              return [];
            }
            nParamDecodeBlock[2] = nParamDecodeBlock[2].replace(new RegExp(xorParam.trim() + '\\s*\\^', 'g'), xorParamValue + '^');
          } else {
            if (stringArrayValue[parseInt(nParamDecodeBlock[4])] != 'set'
              || stringArrayValue[parseInt(nParamDecodeBlock[5])] != 'n') {
              wvApi.reportError(`invalid n block: ${url} ${nParamDecodeBlock[0]}`);
              return [];
            }
          }
        }

        const nparamPlacement = regexReplace(script, /;\s*((?:\w+\.)+init\s*=\s*function)/, ([, g1]) => {
          const res = `; window.ymixNDe = function (${nParamDecodeBlock[3]}) { return ${nParamDecodeBlock[2]}; }; ${g1}`;

          if (DEBUG) console.log(res);
          return res;
        });
        if (!nparamPlacement.length) {
          wvApi.reportError(`n placement not found: ${url}`);
        }
        return nparamPlacement;
      });

      script = regexReplaceFlow('sigdee', url, script, () => {
        const sigPlacement = regexReplace(script, /(\w+\s*=)(\s*function\s*\(\w+,\s*\w+,\s*\w+\)\s*{[^}]+?(?:\.set|\[\w+\[\d+\]\])\("alr",\s*"yes"\)[^{}]+?return \w+\s*}[;,])/, ([match, g1, g2]) => {
          const res = `${g1} window.ymixSigDe = ${g2}`;
          if (DEBUG) console.log(res);
          return res;
        });
        if (!sigPlacement.length) {
          wvApi.reportError(`sig block not found: ${url}`);
        }
        return sigPlacement;
      });
    }
    return script;
  });
}
