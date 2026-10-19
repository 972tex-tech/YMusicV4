#!/bin/bash
# Shipped tags whose host today's bundles must still run on: release-v* / hotfix-v* at or above
# compat/floor.txt. --json prints a JSON array (the CI matrix).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
floor=$(tr -d '[:space:]' < "$here/floor.txt")
tags=$(git -C "$here" tag --list 'release-v*' 'hotfix-v*' | while read -r tag; do
    version=${tag#*-v}
    lowest=$(printf '%s\n%s\n' "$floor" "$version" | sort -V | head -n1)
    [ "$lowest" = "$floor" ] && printf '%s %s\n' "$version" "$tag"
done | sort -V | cut -d' ' -f2)
if [ "${1:-}" = "--json" ]; then
    printf '%s\n' $tags | jq -R . | jq -sc 'map(select(. != ""))'
else
    printf '%s\n' $tags
fi
