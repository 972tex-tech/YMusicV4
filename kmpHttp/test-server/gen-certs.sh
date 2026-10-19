#!/usr/bin/env bash
# Generate the long-lived (100-year) self-signed certs the test server uses. Run once per machine —
# certs are cached in ./certs and reused. Regenerated only when missing or when the current LAN IP is
# not already covered by the server cert's SAN (see start.sh). Not committed (machine/IP specific).
#
#   ./gen-certs.sh <lan-ip>
#
# Produces in ./certs:
#   server.key / server.crt   self-signed server leaf (SAN: <lan-ip>, 127.0.0.1, localhost — as both
#                             IP and DNS entries so hostname verification AND the kmpHttp offline
#                             host-match fallback, which only reads dNSName SANs, both work)
#   client-ca.key/.crt        CA that signs client certs (the mTLS server trusts this)
#   client.key/.crt/.p12      a client identity for the mutual-TLS test (p12 password: kmphttp)
set -euo pipefail
cd "$(dirname "$0")"

IP="${1:?usage: gen-certs.sh <lan-ip>}"
DIR="certs"
DAYS=36500   # ~100 years — effectively no expiry
mkdir -p "$DIR"

SAN="subjectAltName=IP:${IP},DNS:${IP},IP:127.0.0.1,DNS:127.0.0.1,DNS:localhost"

# --- self-signed server leaf ---
openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" \
  -keyout "$DIR/server.key" -out "$DIR/server.crt" \
  -subj "/CN=kmphttp-test-server" -addext "$SAN" >/dev/null 2>&1

# --- client CA ---
openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" \
  -keyout "$DIR/client-ca.key" -out "$DIR/client-ca.crt" \
  -subj "/CN=kmphttp-test-client-ca" >/dev/null 2>&1

# --- client leaf signed by the client CA ---
openssl req -newkey rsa:2048 -nodes \
  -keyout "$DIR/client.key" -out "$DIR/client.csr" \
  -subj "/CN=kmphttp-test-client" >/dev/null 2>&1
openssl x509 -req -in "$DIR/client.csr" -days "$DAYS" \
  -CA "$DIR/client-ca.crt" -CAkey "$DIR/client-ca.key" -CAcreateserial \
  -out "$DIR/client.crt" >/dev/null 2>&1
# Legacy p12 encryption so it imports on OkHttp's KeyStore and NSURLSession's SecPKCS12Import alike:
# OpenSSL 3 needs -legacy for it; macOS's LibreSSL /usr/bin/openssl has no such flag and writes it by default.
LEGACY=""
if openssl pkcs12 -help 2>&1 | grep -q -- '-legacy'; then LEGACY="-legacy"; fi
# shellcheck disable=SC2086 # $LEGACY is empty or one flag
openssl pkcs12 -export -inkey "$DIR/client.key" -in "$DIR/client.crt" \
  -out "$DIR/client.p12" -passout pass:kmphttp $LEGACY >/dev/null 2>&1

rm -f "$DIR/client.csr" "$DIR/client-ca.srl"
echo "Generated certs in $DIR (SAN: $IP, 127.0.0.1, localhost)"
