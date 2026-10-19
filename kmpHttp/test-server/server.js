// Local HTTPS test server for the kmpHttp suite — an httpbin-compatible subset plus the TLS scenarios
// (self-signed, pinning, mutual TLS) that used to depend on live httpbun.com / badssl.com.
//
//   HTTPS_PORT (default 8443)  main server, self-signed leaf (certs/server.crt)
//   MTLS_PORT  (default 8444)  same routes but requires a client cert signed by certs/client-ca.crt
//
// Binds 0.0.0.0 so it's reachable over the LAN IP (host, iOS simulator, and the Linux Docker runner).
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import Fastify from "fastify";

const here = dirname(fileURLToPath(import.meta.url));
const certs = join(here, "certs");
const HTTPS_PORT = Number(process.env.HTTPS_PORT || 8443);
const MTLS_PORT = Number(process.env.MTLS_PORT || 8444);

const key = readFileSync(join(certs, "server.key"));
const cert = readFileSync(join(certs, "server.crt"));
const clientCa = readFileSync(join(certs, "client-ca.crt"));

// httpbin canonicalizes header names to Title-Case in its echo; mirror that so case-sensitive
// assertions (e.g. "X-Custom-Header") hold regardless of the wire casing an engine used.
const titleCase = (name) =>
  name
    .split("-")
    .map((p) => (p ? p[0].toUpperCase() + p.slice(1).toLowerCase() : p))
    .join("-");

function echoHeaders(req) {
  const raw = req.raw.rawHeaders; // [name, value, name, value, ...] — preserves the sent pairs
  const out = {};
  for (let i = 0; i < raw.length; i += 2) out[titleCase(raw[i])] = raw[i + 1];
  return out;
}

function baseInfo(req, body) {
  const url = `${req.protocol}://${req.host}${req.url}`;
  return {
    args: req.query,
    headers: echoHeaders(req),
    url,
    method: req.method,
    data: body ?? "",
    json: tryJson(body),
  };
}

function tryJson(body) {
  if (typeof body !== "string" || body.length === 0) return null;
  try {
    return JSON.parse(body);
  } catch {
    return null;
  }
}

function build(https) {
  const app = Fastify({ bodyLimit: 64 * 1024 * 1024, https });

  // Capture the raw request body as a string for the echo endpoints.
  app.addContentTypeParser("*", { parseAs: "string" }, (_req, body, done) => done(null, body));

  const echo = (req, reply) => reply.send(baseInfo(req, req.body));
  app.get("/get", echo);
  app.post("/post", echo);
  app.put("/put", echo);
  app.patch("/patch", echo);
  app.delete("/delete", echo);
  app.route({ method: ["GET", "POST", "PUT", "PATCH", "DELETE"], url: "/anything", handler: echo });

  app.get("/headers", (req, reply) => reply.send({ headers: echoHeaders(req) }));

  app.get("/status/:code", (req, reply) => reply.code(Number(req.params.code)).send(""));

  // Set arbitrary response headers from the query string (e.g. Cache-Control, Vary, X-Test-Header).
  app.get("/response-headers", (req, reply) => {
    for (const [k, v] of Object.entries(req.query)) reply.header(k, v);
    reply.send({ ...req.query });
  });

  // /redirect/N -> N 302 hops -> /anything (relative Location).
  app.get("/redirect/:n", (req, reply) => {
    const n = Number(req.params.n);
    reply.redirect(n > 1 ? `/redirect/${n - 1}` : "/anything", 302);
  });
  // /absolute-redirect/N -> N 302 hops -> /anything (absolute Location).
  app.get("/absolute-redirect/:n", (req, reply) => {
    const n = Number(req.params.n);
    const base = `${req.protocol}://${req.host}`;
    reply.redirect(n > 1 ? `${base}/absolute-redirect/${n - 1}` : `${base}/anything`, 302);
  });

  // `size` deterministic bytes (byte i = i % 251); honors `Range: bytes=N-` with a 206 and echoes the
  // Range it received in X-Received-Range, so a test can see whether a redirect kept the header.
  app.get("/range/:size", (req, reply) => {
    const size = Number(req.params.size);
    const range = req.headers["range"];
    reply.header("X-Received-Range", range ?? "none");
    reply.header("Content-Type", "application/octet-stream");
    const match = /^bytes=(\d+)-$/.exec(range ?? "");
    const from = match ? Number(match[1]) : 0;
    const body = Buffer.alloc(size - from);
    for (let i = from; i < size; i++) body[i - from] = i % 251;
    if (match) {
      reply.code(206).header("Content-Range", `bytes ${from}-${size - 1}/${size}`);
    }
    reply.send(body);
  });
  app.get("/range-redirect/:size", (req, reply) => {
    reply.redirect(`${req.protocol}://${req.host}/range/${req.params.size}`, 302);
  });

  app.get("/delay/:s", async (req, reply) => {
    await sleep(Number(req.params.s) * 1000);
    reply.send(baseInfo(req, ""));
  });

  // Stream `numbytes` bytes spread over `duration` seconds (like httpbin /drip).
  app.get("/drip", (req, reply) => {
    const numbytes = Number(req.query.numbytes || 10);
    const duration = Number(req.query.duration || 2);
    const code = Number(req.query.code || 200);
    reply.raw.writeHead(code, {
      "Content-Type": "application/octet-stream",
      "Content-Length": String(numbytes),
    });
    let sent = 0;
    const gap = numbytes > 0 ? (duration * 1000) / numbytes : 0;
    const tick = () => {
      if (sent >= numbytes) {
        reply.raw.end();
        return;
      }
      reply.raw.write("*");
      sent++;
      setTimeout(tick, gap);
    };
    tick();
  });

  // Cacheable response: Cache-Control: public, max-age=N + a stable ETag.
  app.get("/cache/:n", (req, reply) => {
    const n = req.params.n;
    reply.header("Cache-Control", `public, max-age=${n}`);
    reply.header("ETag", '"kmphttp-cache"');
    if (req.headers["if-none-match"] === '"kmphttp-cache"') return reply.code(304).send();
    reply.send(baseInfo(req, ""));
  });

  app.get("/cookies", (req, reply) => {
    reply.send({ cookies: parseCookies(req.headers.cookie) });
  });
  // Set each query cookie, then 302 -> /cookies (host-only cookie, no Domain — safe for IP hosts).
  app.get("/cookies/set", (req, reply) => {
    for (const [k, v] of Object.entries(req.query)) {
      reply.header("Set-Cookie", `${k}=${v}; Path=/`);
    }
    reply.redirect("/cookies", 302);
  });

  app.get("/", (_req, reply) => reply.send({ ok: true }));
  return app;
}

function parseCookies(header) {
  const out = {};
  if (!header) return out;
  for (const part of header.split(";")) {
    const i = part.indexOf("=");
    if (i > 0) out[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  return out;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const main = build({ key, cert });
await main.listen({ host: "0.0.0.0", port: HTTPS_PORT }).catch(fail);

// Second instance on MTLS_PORT that requires a client cert signed by the client CA.
const mtls = build({ key, cert, ca: clientCa, requestCert: true, rejectUnauthorized: true });
await mtls.listen({ host: "0.0.0.0", port: MTLS_PORT }).catch(fail);

console.log(`kmphttp-test-server: https :${HTTPS_PORT}  mtls :${MTLS_PORT}`);

function fail(err) {
  console.error(err);
  process.exit(1);
}
