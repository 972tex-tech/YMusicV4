// noinspection JSUnresolvedReference

import { CONST } from '../constants';
import { YMixWebViewApi } from '../webview_api';

export function hookScriptLoad(wvApi: YMixWebViewApi = window.ymixWvApi) {
  if (DEBUG) console.log('__Start intercepting scripts__');
  let deferCount = 0;
  let deferCompleteCount = 0;
  let deferWaitingQueue: { deferId: number; source?: string | null; cb?: () => void }[] = [];

  function evalGlobal(source: string) {
    (function (this: any) {
      if (DEBUG) console.log('eval', this);
      eval.call(this, source);
    }());
  }

  function processDeferredScript(deferId: number, source?: string | null, cb?: () => void) {
    deferWaitingQueue.push({ source, deferId, cb });
    let someHandled;
    do {
      someHandled = false;
      deferWaitingQueue = deferWaitingQueue.filter((item) => {
        if (item.deferId === deferCompleteCount + 1) {
          ++deferCompleteCount;
          someHandled = true;
          if (item.source != null) {
            evalGlobal(item.source);
            // const script = makeTransformedScript();
            // script.innerText = item.source;
            // document.head.appendChild(script);
          }
          item.cb?.();
          return false;
        }
        return true;
      });
    } while (someHandled && deferWaitingQueue.length);
  }

  // botGuard detect eval tampered??
  // let _eval = window.eval;
  // window.eval = function () {
  //     console.log('eval called');
  //     return _eval.apply(this, arguments);
  // };

  function appendQueryParam(url: string | URL, key: string, value: string) {
    if (!(url instanceof URL)) {
      url = new URL(url);
    }
    if (url.searchParams) {
      url.searchParams.append(key, value);
    } else {
      let search = url.search;
      if (search.length > 1) {
        search += '&';
      }
      url.search = search + `${key}=${encodeURIComponent(value)}`;
    }
    return url;
  }

  function xhrFetch(url: string | URL, async: boolean, cb: (source: string | null, error?: Error) => void) {
    url = appendQueryParam(url, CONST.MAGIC_JS_QUERY.NAME, CONST.MAGIC_JS_QUERY.VALUE);
    const request = new XMLHttpRequest();
    request.withCredentials = url.toString().startsWith(window.origin);
    request.open('GET', url, async);
    if (async) {
      request.onloadend = () => {
        if (request.status === 200) {
          cb(request.responseText);
        } else {
          cb(null, Object.assign(new Error(`load script failed: ${request.status} ${url}`), { url }));
        }
      };
    }
    request.send(null);
    if (!async) {
      if (request.status === 200) {
        cb(request.responseText);
      } else {
        cb(null, new Error(`Code ${request.status} ${request.statusText} ${url}`));
      }
    }
  }

  function makeTransformedScript(script?: HTMLScriptElement) {
    script = script || document.createElement('script');
    script.setAttribute('transformed', 'true');
    return script;
  }

  function handleAppendScript(scriptElement: HTMLScriptElement) {
    if (scriptElement.getAttribute('transformed') === 'true') return;
    scriptElement = makeTransformedScript(scriptElement);
    const contentType = scriptElement.getAttribute('type');
    if (contentType
      && contentType !== 'text/javascript' && contentType !== 'application/javascript'
      && contentType !== 'module') {
      return;
    }

    if (scriptElement.src) {
      scriptElement.addEventListener('load', (event: any) => {
        if (event.isIntercepted) return;
        event.stopPropagation();
      }, true);
      scriptElement.addEventListener('error', (event: any) => {
        if (event.isIntercepted) return;
        event.stopPropagation();
      }, true);
      const fireCallback = (success: boolean) => {
        const event: any = new Event(success ? 'load' : 'error');
        Object.defineProperty(event, 'target', { writable: false, value: scriptElement, configurable: true });
        event.isIntercepted = true;
        scriptElement.dispatchEvent(event);
      };

      const scriptSrcUrl = scriptElement.src;
      const isAsync = scriptElement.async;
      const isDefer = scriptElement.defer || contentType === 'module';
      scriptElement.async = scriptElement.defer = false;
      scriptElement.type = 'script/blocked';
      if (scriptSrcUrl.includes('/endscreen.js')
        || scriptSrcUrl.includes('/annotations_module.js')
        || scriptSrcUrl.includes('/remote.js')
        || scriptSrcUrl.includes('doubleclick')
      ) {
        return;
      }
      let scriptFetchUrl = scriptSrcUrl;
      // scriptFetchUrl = scriptFetchUrl.replace('player_es6.vflset', 'player_ias.vflset');
      // scriptFetchUrl = scriptFetchUrl.replace('player_es5.vflset', 'player_ias.vflset');
      // scriptFetchUrl = scriptFetchUrl.replace('player_remote_ad.vflset', 'player_ias.vflset');
      // scriptFetchUrl = scriptFetchUrl.replace('player_embed.vflset', 'player_ias.vflset');
      // scriptFetchUrl = scriptFetchUrl.replace('player_embed_es6.vflset', 'player_ias.vflset');
      // scriptFetchUrl = scriptFetchUrl.replace('player_embed_es5.vflset', 'player_ias.vflset');
      scriptFetchUrl = scriptFetchUrl.replace(/\/player_\w+\.vflset/g, '/player_ias.vflset');

      if (scriptFetchUrl.includes('google.com/js/th/')) {
        scriptFetchUrl = appendQueryParam('https://ymusicapp.com/hello/getScript.js', 'url', scriptFetchUrl).toString();
      }

      // https://www.youtube.com/s/player/da13af8d/player_ias.vflset/vi_VN/base.js
      // scriptFetchUrl = scriptFetchUrl.replace('da13af8d', 'a1f2424c');

      if (scriptSrcUrl != scriptFetchUrl) {
        window.handleYtInternalLog?.('info', 'ymix', `script url ${scriptSrcUrl} -> ${scriptFetchUrl}`);
      }

      const deferId = isDefer ? ++deferCount : undefined;
      xhrFetch(scriptFetchUrl, isAsync || isDefer, (data, error) => {
        if (error) {
          window.handleYtInternalLog?.('error', 'ymix', error?.stack);
          wvApi.reportError(`Load script failure: ${error.message}`, `${scriptFetchUrl} \n ${scriptSrcUrl}`);
        }
        if (deferId) {
          processDeferredScript(
            deferId,
            error ? null : wvApi.transformScript(data as string, scriptFetchUrl, scriptSrcUrl),
            () => fireCallback(error == null),
          );
          return;
        }

        if (error == null) {
          evalGlobal(wvApi.transformScript(data as string, scriptFetchUrl, scriptSrcUrl));
          // const script = makeTransformedScript();
          // script.setAttribute('transformed-from-src', scriptSrcUrl);
          // script.textContent = wvApi.transformScript(data as string, scriptSrcUrl);
          // document.head.appendChild(script);
        }
        fireCallback(error == null);
      });
    } else {
      const originScript = scriptElement.textContent?.trim();
      if (originScript) {
        scriptElement.textContent = wvApi.transformScript(originScript, undefined);
      }
    }
  }

  const _originAppendChild = Element.prototype.appendChild;
  Element.prototype.appendChild = function (this: any, element: any) {
    if (element instanceof HTMLScriptElement) {
      handleAppendScript(element);
    }
    // eslint-disable-next-line prefer-rest-params
    return _originAppendChild.apply(this, arguments as any);
  } as any;
  const _originInsertBefore = Element.prototype.insertBefore;
  Element.prototype.insertBefore = function (this: any, element: any) {
    if (element instanceof HTMLScriptElement) {
      handleAppendScript(element);
    }
    // eslint-disable-next-line prefer-rest-params
    return _originInsertBefore.apply(this, arguments as any);
  } as any;

  const mutationObserverCallback: MutationCallback = (mutationList) => {
    mutationList.forEach((mutation) => {
      if (mutation.type === 'childList') {
        const addedNodes = mutation.addedNodes;
        for (let i = 0; i < addedNodes.length; i++) {
          const addedNode = addedNodes[i];
          if (addedNode instanceof HTMLScriptElement) {
            handleAppendScript(addedNode);
          }
        }
      }
    });
  };

  const observer = new MutationObserver(mutationObserverCallback);
  observer.observe(document, {
    childList: true,
    subtree: true,
  });
}
