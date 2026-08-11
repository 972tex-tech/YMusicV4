#!/usr/bin/env bash
# Generate the local test media the standalone MSE harness (index.html / index_ct.html) fetches.
# These are NOT committed (a few MB); regenerate with ffmpeg. Muted content, lab-only.
set -euo pipefail
cd "$(dirname "$0")"
ffmpeg -y -loglevel error -f lavfi -i testsrc2=s=320x180:r=30:d=90 \
  -c:v libvpx-vp9 -b:v 200k -keyint_min 30 -g 30 -pix_fmt yuv420p real_v.webm
ffmpeg -y -loglevel error -f lavfi -i "sine=frequency=440:duration=90" \
  -c:a libopus -b:a 96k real_a.webm
ffmpeg -y -loglevel error -f lavfi -i testsrc2=s=320x180:r=30:d=90 \
  -c:v libx264 -profile:v baseline -b:v 200k -g 30 -keyint_min 30 -pix_fmt yuv420p \
  -movflags frag_keyframe+empty_moov+default_base_moof -f mp4 real_v_avc.mp4
echo "generated real_v.webm real_a.webm real_v_avc.mp4"
