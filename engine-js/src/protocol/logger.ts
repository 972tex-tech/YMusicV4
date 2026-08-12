// The JS half of the logging-context channel (HOST_PROTOCOLS "Logging context"): an immutable
// crumb-trail logger mirroring kmpcommon.log.Logger. A host command mints one from its args head
// ("logKey" — the key of the Kotlin caller's parked logger), forks its own hops, and attaches it
// to host calls; the wire shape ("log": {"key","trail"}) grafts the trail back onto the parked
// logger so host birth-site logs join the caller's story. There is no log() here — console output
// already crosses on its own channel.

// One trail hop: [tag] or [tag, props]. Tags are STABLE names (crash grouping keys are built from
// tag paths); anything variable (ids, params) goes in the props object, values JSON primitives.
// Wire keys quoted — these cross to Kotlin as JSON (LogContext.kt).
export type LogCrumb = [string] | [string, Record<string, unknown>];

// Every JS-side tag is "js:"-prefixed (JS hops stay distinguishable from Kotlin ones in a trail)
// and slash-free ("/" is the rendered path separator — a slash inside a tag would fake two hops).
function jsTag(tag: string): string {
  const t = tag.replace(/\//g, "-");
  return t.indexOf("js:") === 0 ? t : "js:" + t;
}

export interface LogWire {
  "key"?: number;
  "trail": LogCrumb[];
}

export class JsLogger {
  private key: number | null;
  private trail: LogCrumb[];

  constructor(key: number | null, trail: LogCrumb[]) {
    this.key = key;
    this.trail = trail;
  }

  fork(tag: string, prop?: Record<string, unknown>): JsLogger {
    const t = this.trail.slice();
    const name = jsTag(tag);
    t.push(prop === undefined ? [name] : [name, prop]);
    return new JsLogger(this.key, t);
  }

  wire(): LogWire {
    const w: LogWire = { "trail": this.trail };
    if (this.key !== null) w["key"] = this.key;
    return w;
  }
}

// The logger for one host-command invocation. A parked Kotlin caller (a "logKey" in the args
// head) already forked its own hop naming this call — starting the JS trail empty avoids the
// duplicate; only a keyless invocation keeps the command [tag] so its trail isn't anonymous.
export function hostLogger(args: Record<string, unknown>, tag: string): JsLogger {
  const k = args["logKey"];
  return typeof k === "number" ? new JsLogger(k, []) : new JsLogger(null, [[jsTag(tag)]]);
}

// A keyless logger for JS-initiated work (session bootstrap, crons) — resolves to the engine's
// root logger host-side.
export function jsLogger(tag: string): JsLogger {
  return new JsLogger(null, [[jsTag(tag)]]);
}
