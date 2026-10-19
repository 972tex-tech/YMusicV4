// HttpRequest — a small OkHttp-`Request.Builder`-shaped fluent client over the :quickjs pipe
// protocol (consistent with :kmpHttp, which ports the OkHttp API). Start a builder with
// HttpRequest.get(url) / .post(url) / … / .method(m, url); chain header/body/option setters; then
// .build() -> HttpCall — call.execute() resolves the Response (once), call.cancel() aborts at ANY
// stage. Full surface: the ambient contract in src/types.d.ts (HttpRequestApi, HttpRequestBuilder,
// HttpCall, HttpResponse) — the implementations here are checked against it. Wire shape: ./wire.ts
// (server side: HttpProtocol.kt). Layout: headers.ts (validation + the header view), builder.ts,
// call.ts (the exchange), response.ts (+ body streaming/parse). WHATWG `fetch` / `XMLHttpRequest`
// live in logic/yt (they cook the YouTube cookie policy onto the builder). Host-classified errors
// are decoded by hostError in ../command.
// Bundled+minified into the :engine-js artifacts (scrape `engine.js`, player `player.js`); never shipped as source.

import { HttpRequestBuilderImpl } from "./builder";
import { addInterceptor } from "./intercept";

function verb(method: string): (url: string) => HttpRequestBuilder {
  return function (url: string) {
    return new HttpRequestBuilderImpl(method, url);
  };
}

export const HttpRequest: HttpRequestApi = {
  get: verb("GET"),
  post: verb("POST"),
  put: verb("PUT"),
  delete: verb("DELETE"),
  patch: verb("PATCH"),
  head: verb("HEAD"),
  method: function (m: string, url: string) {
    return new HttpRequestBuilderImpl(String(m).toUpperCase(), url);
  },
  "addInterceptor": addInterceptor,
};
