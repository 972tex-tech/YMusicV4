#!/bin/sh
# Feeds conveyor.conf (see its `include required("#!./conveyor-config.sh")`).
#
# Two steps, because Conveyor parses this script's STDOUT as config: build the ProGuard-shrunk
# jars — ProGuard writes its banner and warnings to stdout, so that step is redirected to stderr —
# then print the Gradle-exported config on a clean stdout.
#
# -Pymusic.debugLogs=false is the release knob (kmpCommon BuildFlags + desktop DEBUG_BUILD), so
# every Conveyor package is a release build by construction.
set -e
cd "$(dirname "$0")"
./gradlew -q -Pymusic.debugLogs=false :desktopApp:conveyorJars 1>&2
./gradlew -q -Pymusic.debugLogs=false :desktopApp:printConveyorConfig
