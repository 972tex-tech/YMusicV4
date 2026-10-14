// YouTube sign-in commands (Kotlin twin: yt/YtLoginCommands.kt). The app opens a browser WebView on
// the entry URL and lets the user complete Google's sign-in; those requests write their cookies into
// the SAME host jar the scraper reads (cookies.ts). This surface is how the app then learns the
// outcome:
//   yt/login/url      -> the entry URL (engine-owned, so it ships without an app release)
//   yt/login/status   -> the sign-in gate, COOKIE-ONLY (no network): a half-set jar hit with an
//                        authenticated call gets its cookies revoked, so the poll never touches the
//                        network — it only inspects the jar.
//   yt/account        -> the account card (name/email/channel), from account_menu — called ONLY once
//                        the gate has passed and the cookies are settled.
//   yt/logout         -> drop the auth cookies, keeping the anonymous identity (PREF/visitor/YSC).
//   yt/verify/target  -> where the user clears a "captcha" bot challenge, + the UA to present.

import { debug } from "../../debug";
import { onAccept } from "../../transport";
import { commandHandler } from "../../protocol/command";
import { Cookies } from "../../protocol/cookie";
import { Cache } from "../../protocol/cache";
import { DESKTOP_UA, getSession, invalidateSession } from "./session";
import { innertubeJson } from "./innertube";
import { setLoginInfoWipedHandler } from "./cookies";
import { asString, get, isObj, textOf, thumbsOf, walk } from "./scan";
import type { WireAccount, WireVerifyTarget } from "./wire";

const YT = "https://www.youtube.com";
const YT_MOBILE = "https://m.youtube.com";

// The account card is cached separately from the session (its own scope, sign-in/out lifecycle —
// legacy caches YTUser apart from the essential-headers store, NOT inside the session). Served
// cached-first so the app shows the identity instantly on start with no authenticated call;
// re-fetched only on `{refresh:true}` (a sign-in), cleared on sign-out / a logged-out gate.
const ACCOUNT_SCOPE = "yt-account";
const ACCOUNT_KEY = "card";

// The Logout?continue= form forces a fresh Google account chooser instead of silently reusing an
// existing browser session (legacy YTLoginInfoGenerator). Bundle-updatable — the app must not
// hardcode it.
const LOGIN_URL =
  "https://accounts.google.com/Logout?continue=https://m.youtube.com/signin?app=mobile%26persist_app=1";

// Any one of these, non-empty, in BOTH the www and m jars marks a completed sign-in. The set spans
// the whole auth cookie family (set together at login completion): the JS-visible members
// (SAPISID / APISID / SIDCC / __Secure-1PAPISID / __Secure-3PAPISID) guarantee detection even when
// the httpOnly names (LOGIN_INFO / HSID / SSID) don't surface from the host jar. The __Secure PAPISID
// pair is also what feeds the multi-hash SAPISIDHASH (sapisid.ts).
const ESSENTIAL = [
  "LOGIN_INFO",
  "SAPISID",
  "APISID",
  "HSID",
  "SSID",
  "SIDCC",
  "__Secure-1PAPISID",
  "__Secure-3PAPISID",
];
// Kept on logout — the anonymous identity YouTube needs for a normal logged-out feed.
const KEEP_ON_LOGOUT: Record<string, true> = {
  "PREF": true,
  "VISITOR_INFO1_LIVE": true,
  "YSC": true,
};

function jarSignedIn(map: Record<string, string>): boolean {
  for (const name of ESSENTIAL) {
    const v = map[name];
    if (v !== undefined && v.length > 0) return true;
  }
  return false;
}

/** The cookie-only gate: an essential cookie present in BOTH the www and m jars. No network. */
async function isSignedIn(): Promise<boolean> {
  const www = await Cookies.get(YT);
  const mobile = await Cookies.get(YT_MOBILE);
  return jarSignedIn(www) && jarSignedIn(mobile);
}

async function writeJson(out: PipeSink, value: unknown): Promise<void> {
  const p = out.writeObjectAsJson(value);
  if (p) await p;
}

/** The account card off account_menu, or null when the response holds NO active account — the
 *  signed-out shape a REVOKED jar gets: cookies present, nobody behind them. */
function parseAccount(root: unknown): WireAccount | null {
  let header: unknown;
  walk(root, (key, value) => {
    if (key === "activeAccountHeaderRenderer") {
      header = value;
      return true;
    }
  });
  if (header === undefined) return null;
  // The user's own channel: the first "UC…" browse target in the menu (the My-channel link).
  let channelId: string | undefined;
  walk(root, (key, value) => {
    if (key !== "browseEndpoint" || channelId !== undefined) return;
    const id = asString(get(value, "browseId"));
    if (id !== undefined && id.indexOf("UC") === 0) channelId = id;
  });

  const acct: WireAccount = { "signedIn": true };
  const name = textOf(get(header, "accountName"));
  if (name !== undefined) acct["name"] = name;
  const email = textOf(get(header, "email"));
  if (email !== undefined) acct["email"] = email;
  const handle = textOf(get(header, "channelHandle"));
  if (handle !== undefined) acct["handle"] = handle;
  if (channelId !== undefined) acct["channelId"] = channelId;
  const thumbs = thumbsOf(get(header, "accountPhoto"));
  if (thumbs !== undefined) acct["thumbs"] = thumbs;
  return acct;
}

/** The cached account card (an object with signedIn=true), or undefined on a miss / no store. */
async function readCachedAccount(): Promise<Record<string, unknown> | undefined> {
  try {
    const raw = await Cache.getJson(ACCOUNT_SCOPE, ACCOUNT_KEY);
    if (isObj(raw) && raw["signedIn"] === true) return raw;
  } catch (e) {
    debug(function () {
      return "yt account cache read skipped: " + errText(e);
    });
  }
  return undefined;
}

async function cacheAccount(acct: WireAccount): Promise<void> {
  try {
    await Cache.setJson(ACCOUNT_SCOPE, ACCOUNT_KEY, acct);
  } catch (e) {
    debug(function () {
      return "yt account cache write skipped: " + errText(e);
    });
  }
}

async function clearCachedAccount(): Promise<void> {
  try {
    await Cache.delete(ACCOUNT_SCOPE, ACCOUNT_KEY);
  } catch (e) {
    debug(function () {
      return "yt account cache clear skipped: " + errText(e);
    });
  }
}

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

// The remote-logout action (detection lives in cookies.ts, on the LOGIN_INFO wipe): the rest of the
// dead auth set goes too, and the cached card with it, so the cookie gate answers signed-out.
setLoginInfoWipedHandler(async () => {
  await clearAuthCookies(YT);
  await clearAuthCookies(YT_MOBILE);
  await clearCachedAccount();
  await invalidateSession();
});

async function clearAuthCookies(origin: string): Promise<void> {
  const map = await Cookies.get(origin);
  const expired: string[] = [];
  for (const name in map) {
    if (KEEP_ON_LOGOUT[name] === true) continue;
    // Secure is REQUIRED: Chromium-family stores reject any __Secure-* write without it — the
    // expiry would silently no-op and the auth cookies survive the sign-out (seen live 2026-07-31).
    expired.push(
      name + "=; Domain=.youtube.com; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure",
    );
  }
  if (expired.length > 0) await Cookies.set(origin, expired);
}

// The sign-in entry URL -> {"url": …}. The app queries this rather than hardcoding it.
onAccept(
  "yt/login/url",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    await writeJson(out, { "url": LOGIN_URL });
  }),
);

// The account-switcher entry URL -> {"url": …}. Opened like the sign-in browser but with NO cookie
// polling — the switcher swaps identities within an already-valid jar, so there's no gate to flip;
// the app refreshes session+account when the user closes the screen instead.
const SWITCH_URL = "https://m.youtube.com/channel_switcher?app=m&persist_app=1";

onAccept(
  "yt/login/switch-url",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    await writeJson(out, { "url": SWITCH_URL });
  }),
);

// Where the user clears a "captcha" bot challenge -> WireVerifyTarget. The browser must present the
// scrape's own User-Agent and write the shared jar, or the cleared challenge belongs to another
// identity. No polling: closing the browser is the completion signal.
onAccept(
  "yt/verify/target",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    const target: WireVerifyTarget = { "url": YT + "/", "userAgent": DESKTOP_UA };
    await writeJson(out, target);
  }),
);

// The sign-in gate -> {"signedIn": bool}. COOKIE-ONLY — the login screen polls this; it must never
// hit the network mid sign-in (YouTube revokes a half-set jar).
onAccept(
  "yt/login/status",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    await writeJson(out, { "signedIn": await isSignedIn() });
  }),
);

// The account card -> WireAccount. Args {"refresh"?}. Cached-first: a signed-in jar with a cached
// card serves it with NO network; {"refresh":true} (a fresh sign-in) forces account_menu and
// re-caches. A not-signed-in jar answers {"signedIn": false}, clearing any stale cache, with no call.
onAccept(
  "yt/account",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    if (!(await isSignedIn())) {
      await clearCachedAccount();
      await invalidateSession();
      await writeJson(out, { "signedIn": false });
      return;
    }
    if (args["refresh"] !== true) {
      const cached = await readCachedAccount();
      if (cached !== undefined) {
        await writeJson(out, cached);
        return;
      }
    }
    const acct = parseAccount(await innertubeJson("account/account_menu", {}));
    if (acct === null) {
      // No account header in the response. NOT a logout signal by itself — that could be a
      // markup change our parser missed, and a parser miss must never sign the user out. The
      // trustworthy signal is the server wiping LOGIN_INFO, which the fetch above already
      // triggered through storeSetCookies if the session was really revoked — so re-ask the
      // cookie gate: revoked → false; a parser miss over a live jar stays signed in (bare
      // card, never cached).
      await writeJson(out, { "signedIn": await isSignedIn() });
      return;
    }
    await cacheAccount(acct);
    await writeJson(out, acct);
  }),
);

// Sign out -> {"signedIn": false}. Drops the auth cookies in both jars, keeping the anonymous set.
onAccept(
  "yt/logout",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    console.info("DBG logout start");
    await clearAuthCookies(YT);
    console.info("DBG logout www cleared");
    await clearAuthCookies(YT_MOBILE);
    console.info("DBG logout m cleared");
    await clearCachedAccount();
    console.info("DBG logout account cache cleared");
    await invalidateSession();
    console.info("DBG logout session invalidated");
    await writeJson(out, { "signedIn": false });
  }),
);

// Drop the cached session and re-scrape under the CURRENT cookie identity -> {"ok": true}. Called
// after sign-in, sign-out, or account switch so SESSION_INDEX / delegated id match the jar — a
// failed scrape must not fall back to the previous account. A homepage GET, safe once the gate has
// passed (never an authenticated call mid sign-in).
onAccept(
  "yt/session/refresh",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    console.info("DBG refresh start");
    await invalidateSession();
    console.info("DBG refresh invalidated");
    await getSession(0);
    console.info("DBG refresh done");
    await writeJson(out, { "ok": true });
  }),
);
