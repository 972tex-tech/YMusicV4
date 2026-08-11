// Engine lifecycle, served over pipes like every other capability. The host drives "init" right
// after the bundle eval — a health check proving the whole stack (open routing, handler dispatch,
// JSON encode, END) and returning the bundle's self-reported revision — and "shutdown" when this
// engine is retired for a newer bundle: Node's SIGINT, as a pipe.
//
// Shutdown is acked by OBSERVATION, not by reply: clearing timers and finishing callbacks closes
// their pipes, and the host waits for its pipe map to drain (QuickJsRuntime.awaitIdle). Cleanup
// that matters must run in an onShutdown callback — after the runtime closes there is no JS.
import { onAccept } from "./transport";
import { commandHandler } from "./protocol/command";

type ShutdownCb = () => void | Promise<void>;
let shuttingDown = false;
const shutdownCbs: ShutdownCb[] = [];

/** True once the host signaled retirement: don't start new background work. */
export function isShuttingDown(): boolean {
  return shuttingDown;
}

/** Register cleanup to run at shutdown: whoever starts repeat work (timers, crons) is the one
 *  responsible for stopping it here — clear your own intervals, await what's in flight, release
 *  what you hold. A live timer nobody clears keeps its pipe open, so the engine never drains and
 *  the host's deadline force-closes it. Runs once, in registration order; removable like
 *  ymix.onAccept's registration. */
export function onShutdown(cb: ShutdownCb): { remove(): void } {
  shutdownCbs.push(cb);
  return {
    remove(): void {
      const i = shutdownCbs.indexOf(cb);
      if (i >= 0) shutdownCbs.splice(i, 1);
    },
  };
}

onAccept(
  "init",
  commandHandler(async (_src, out) => {
    const p = out.writeObjectAsJson({ "backendVersion": BACKEND_VERSION });
    if (p) await p;
  }),
);

onAccept(
  "shutdown",
  commandHandler(async () => {
    shuttingDown = true;
    const cbs = shutdownCbs.slice();
    shutdownCbs.length = 0;
    for (const cb of cbs) {
      try {
        await cb();
      } catch (e) {
        // one bad callback must not wedge the rest of shutdown (the drain deadline backstops)
        console.error("onShutdown callback failed:", e);
      }
    }
    // returning ENDs the pipe — the host's EOF is the "quiesced" ack
  }),
);
