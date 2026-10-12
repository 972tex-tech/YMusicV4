#!/usr/bin/env bash
# Seal the build secrets — secrets.properties and the Android keystore under signing/ — into
# secrets.enc, which is committed; ./decrypt.sh restores them. local.properties stays per machine.
#
#   ./encrypt.sh        prompts for the passphrase (twice)
#
# AES-256-CBC, key from PBKDF2-HMAC-SHA512 at 1,000,000 iterations with a random salt. The
# plaintext carries its own SHA-256, checked by decrypt.sh before anything is written, so a wrong
# passphrase or a tampered file is refused rather than restored. secrets.enc is public with the
# repository: only a long random passphrase keeps it closed against offline guessing.
set -euo pipefail
cd "$(dirname "$0")"

OUT=secrets.enc
ITER=1000000

[[ -f secrets.properties ]] || { echo "missing secrets.properties (see secrets.properties.example)" >&2; exit 1; }
files=(secrets.properties)
if [[ -d signing ]]; then
  while IFS= read -r f; do files+=("$f"); done < <(find signing -type f ! -name .DS_Store | sort)
fi

read -rsp "Passphrase: " passphrase; echo
read -rsp "Again: " again; echo
[[ $passphrase == "$again" ]] || { echo "passphrases differ" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# ustar, and no macOS metadata, so GNU tar on Linux extracts it cleanly.
COPYFILE_DISABLE=1 tar --format=ustar -cf "$work/payload.tar" "${files[@]}"
sum=$(openssl dgst -sha256 -r "$work/payload.tar" | cut -d' ' -f1)

{ echo "$sum"; cat "$work/payload.tar"; } |
  openssl enc -aes-256-cbc -salt -pbkdf2 -iter "$ITER" -md sha512 -pass fd:3 \
    -out "$OUT.tmp" 3< <(printf '%s\n' "$passphrase")
mv "$OUT.tmp" "$OUT"
echo "sealed ${files[*]} → $OUT"
