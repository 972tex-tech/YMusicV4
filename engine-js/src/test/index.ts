// Entry for the TEST bundle (engine-test.js): the whole production bundle plus the test fixtures
// the Kotlin test suites drive via pipes. Built by the same pipeline as production so tests run on
// representative output.
import "../index";
import "./fixtures/protocol";
import "./fixtures/logging";
import "./fixtures/http";
import "./fixtures/cookie";
import "./fixtures/cache";
import "./fixtures/settings";
import "./fixtures/transport";
import "./fixtures/runtime";
import "./fixtures/stress";
import "./fixtures/lifecycle";
