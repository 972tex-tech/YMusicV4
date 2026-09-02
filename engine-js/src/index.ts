// Entry for the single engine bundle: everything the QuickJS engine runs ships from here as ONE
// esbuild artifact (see build.gradle.kts) — the script QuickJsRuntime.create evals once at
// creation. Import order is load order.
import "./console"; // console over the __consoleLog native — first, so boot code can log
import "./transport"; // pipe transport JS half: ymix, __js*, timers
import "./protocol/command"; // commandHandler + JsEngineError (the error contract)
import "./protocol/http"; // HttpRequest over the "http" pipe command
import "./protocol/mse"; // MediaSource / SourceBuffer / HTMLMediaElement → __mse* C host
// protocol/cookie (Cookies over "cookie/get"/"cookie/set") has no side effects — it is pulled in by
// whichever scraping module imports { Cookies }, not registered here.
import "./lifecycle"; // "init" health check + "shutdown" (SIGINT as a pipe)
import "./logic"; // scraping logic
