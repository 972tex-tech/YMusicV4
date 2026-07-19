#!/usr/bin/env bash
# Start the kmpHttp test server as a DETACHED daemon and leave it running (it outlives the build).
# Idempotent: if it's already answering on the LAN IP it does nothing. Writes server-info.json
# describing where it's reachable, so the Gradle side can generate the Kotlin TestServer constants.
#
#   ./start.sh          # ensure the server is up; print + persist server-info.json
#
# Reachability: binds the LAN IP (not just localhost) so the iOS simulator and the Linux Docker
# runner can reach the host. Certs are generated once (100-year) and reused; regenerated only if the
# current LAN IP isn't in the server cert's SAN.
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v node >/dev/null 2>&1; then
  echo "ERROR: node is required to run the kmpHttp test server (test-server/). Install Node 20+." >&2
  echo "       macOS: brew install node   Debian/Ubuntu: see docker/linux-test.Dockerfile" >&2
  exit 1
fi

HTTPS_PORT="${HTTPS_PORT:-8443}"
MTLS_PORT="${MTLS_PORT:-8444}"

# Primary non-internal IPv4 (portable across macOS/Linux via Node's os module).
IP="$(node -e 'const n=require("os").networkInterfaces();let ip="127.0.0.1";for(const k of Object.keys(n))for(const a of n[k])if(a.family==="IPv4"&&!a.internal){ip=a.address;break}console.log(ip)')"

# (Re)generate certs if missing or the current IP isn't covered by the server cert's SAN.
need_certs=0
if [[ ! -f certs/server.crt || ! -f certs/client.p12 ]]; then
  need_certs=1
elif ! openssl x509 -in certs/server.crt -noout -text 2>/dev/null | grep -q "IP Address:${IP}\b\|DNS:${IP}\b"; then
  need_certs=1
fi
if [[ "$need_certs" == 1 ]]; then
  ./gen-certs.sh "$IP"
fi

writeInfo() {
  cat > server-info.json <<JSON
{"ip":"${IP}","httpsPort":${HTTPS_PORT},"mtlsPort":${MTLS_PORT}}
JSON
  echo "server-info: ip=${IP} https=${HTTPS_PORT} mtls=${MTLS_PORT}"
}

# Already up on this IP AND serving the current on-disk cert? Then we're done — leave it running.
# The cert comparison matters: a daemon outlives cert regeneration, and a stale served cert fails
# every client's trust evaluation as an instant NSURLErrorCancelled/-999.
disk_fpr="$(openssl x509 -in certs/server.crt -noout -fingerprint -sha256 | cut -d= -f2)"
served_fpr() {
  echo | openssl s_client -connect "${IP}:${HTTPS_PORT}" 2>/dev/null \
    | openssl x509 -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2
}
if curl -ksS --max-time 3 "https://${IP}:${HTTPS_PORT}/" >/dev/null 2>&1; then
  if [[ "$(served_fpr)" == "$disk_fpr" ]]; then
    echo "test server already running on ${IP}:${HTTPS_PORT}"
    writeInfo
    exit 0
  fi
  echo "test server on ${IP}:${HTTPS_PORT} serves a stale cert — restarting it"
  if [[ -f server.pid ]] && kill -0 "$(cat server.pid)" 2>/dev/null; then
    kill "$(cat server.pid)" 2>/dev/null || true
  fi
  # Fallback for a daemon whose pid file is gone (or from another checkout).
  lsof -ti "tcp:${HTTPS_PORT}" 2>/dev/null | xargs kill 2>/dev/null || true
  for _ in $(seq 1 20); do
    curl -ksS --max-time 1 "https://${IP}:${HTTPS_PORT}/" >/dev/null 2>&1 || break
    sleep 0.5
  done
fi

# Install deps once.
if [[ ! -d node_modules ]]; then
  echo "installing test-server deps..."
  npm install --no-audit --no-fund >/dev/null 2>&1
fi

echo "starting test server (detached)..."
HTTPS_PORT="$HTTPS_PORT" MTLS_PORT="$MTLS_PORT" nohup node server.js > server.log 2>&1 &
echo $! > server.pid
disown || true

# Wait for readiness (up to ~15s).
for _ in $(seq 1 30); do
  if curl -ksS --max-time 2 "https://${IP}:${HTTPS_PORT}/" >/dev/null 2>&1; then
    echo "test server up on ${IP}:${HTTPS_PORT} (pid $(cat server.pid))"
    writeInfo
    exit 0
  fi
  sleep 0.5
done

echo "ERROR: test server did not become ready; see $(pwd)/server.log" >&2
tail -20 server.log >&2 || true
exit 1
