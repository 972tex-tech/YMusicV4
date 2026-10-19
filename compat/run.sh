#!/bin/bash
# Today's engine.js / player.js (built from the working tree, uncommitted edits included) against
# the host of every shipped tag from compat/floor.txt on. Each tag gets a reused worktree under
# $COMPAT_DIR; pass tags to check only those.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")
work=${COMPAT_DIR:-${TMPDIR:-/tmp}/ymusic-compat}
mkdir -p "$work/bundles" "$work/wt"

(cd "$root" && ./gradlew -q -Pproduction=true :engine-js:closureJs :engine-js:closurePlayerJs)
cp "$root/engine-js/build/dist/engine.js" "$root/engine-js/build/dist/player.js" "$work/bundles/"

tags=("$@")
[ ${#tags[@]} -gt 0 ] || read -r -a tags <<< "$("$here/tags.sh" | tr '\n' ' ')"

failed=()
for tag in "${tags[@]}"; do
    checkout="$work/wt/$tag"
    if [ ! -d "$checkout" ]; then
        git -C "$root" worktree add --detach "$checkout" "$tag" > /dev/null
    fi
    [ -f "$root/local.properties" ] && cp "$root/local.properties" "$checkout/"
    "$here/overlay.sh" "$checkout" "$work/bundles"
    echo "== $tag (host versionCodes: $(tr '\n' ' ' < "$checkout/dynamic-engine/build/compat/app-versions"))"
    if (cd "$checkout" && ./gradlew -q :dynamic-engine:jvmTest --tests 'io.ymusic.dynamicengine.compat.*' --rerun); then
        echo "✅ $tag"
    else
        echo "❌ $tag — today's bundles break this shipped host"
        failed+=("$tag")
    fi
done
[ ${#failed[@]} -eq 0 ] || { echo "broken: ${failed[*]}"; exit 1; }
