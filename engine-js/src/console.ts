// console over the __consoleLog C native — developer breadcrumbs, never the error channel
// (errors cross as classified RESETs). A direct native rather than a pipe: logging must work from
// the first instruction of the boot eval and when the pipe layer itself is sick. The host maps
// levels onto its Logger; nothing logged is ever shown to the user.

const MSG_CAP = 8192;

function fmt(args: unknown[]): string {
  let s = "";
  for (let i = 0; i < args.length; i++) {
    if (i > 0) s += " ";
    const a = args[i];
    if (typeof a === "string") {
      s += a;
    } else if (a instanceof Error) {
      s += String(a) + (a.stack ? "\n" + a.stack : "");
    } else {
      // Diagnostics-only stringify: property names of internal objects appear MANGLED here
      // (Closure ADVANCED) — expected; wire shapes are the only structural JSON in the bundle.
      try {
        s += JSON.stringify(a) ?? String(a);
      } catch (_e) {
        s += String(a);
      }
    }
    if (s.length > MSG_CAP) return s.slice(0, MSG_CAP);
  }
  return s;
}

const nConsoleLog = __consoleLog;
function emit(level: number): (...args: unknown[]) => void {
  return function (...args: unknown[]): void {
    nConsoleLog(level, fmt(args));
  };
}

globalThis.console = { log: emit(0), info: emit(1), warn: emit(2), error: emit(3) };
