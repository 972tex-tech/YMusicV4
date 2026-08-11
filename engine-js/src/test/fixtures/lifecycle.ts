// Fixtures for the manager/shutdown tests: background work that must stop at "shutdown", and a
// window into the lifecycle state (host protocols stay served while a retired engine drains, so
// Kotlin can read the flags after signaling).
import { onAccept, setInterval, clearInterval } from "../../transport";
import { commandHandler } from "../../protocol/command";
import { onShutdown, isShuttingDown } from "../../lifecycle";

let cbRan = false;
onShutdown(() => {
  cbRan = true;
});

// The ownership pattern for repeat work: whoever starts a timer clears it in its own onShutdown
// (a live timer's pipe holds the engine out of idle — nobody mass-clears on the owner's behalf).
onAccept(
  "test/lifecycle/start-cron",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    const cron = setInterval(() => {}, 60000);
    onShutdown(() => clearInterval(cron));
    const p = out.writeUtf8("started");
    if (p) await p;
  }),
);

// Console from inside a served command (the common case: scraping code leaving breadcrumbs).
onAccept(
  "test/lifecycle/log",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    console.log("crumb", 42, { "shape": true });
    console.error("boom", new Error("dead end"));
    const p = out.writeUtf8("logged");
    if (p) await p;
  }),
);

onAccept(
  "test/lifecycle/state",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    const p = out.writeObjectAsJson({ "shuttingDown": isShuttingDown(), "cbRan": cbRan });
    if (p) await p;
  }),
);
