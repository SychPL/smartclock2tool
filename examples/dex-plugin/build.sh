#!/usr/bin/env bash
# Builds a plugin .dex for the agent's /agent/dex route.
#
#   ./build.sh                    # builds HelloPlugin.java -> out/dex/classes.dex
#   ./build.sh MyPlugin.java      # builds your own file (same package layout)
#
# Needs a JDK (javac) and an Android SDK with build-tools (d8). Set ANDROID_HOME
# if the SDK is not in the usual place.
set -euo pipefail

SRC="${1:-HelloPlugin.java}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
    echo "FATAL: set ANDROID_HOME to your Android SDK" >&2
    exit 1
fi

BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)/android.jar"
if [ -z "$BT" ] || [ ! -f "$PLATFORM" ]; then
    echo "FATAL: no build-tools or platform in $SDK" >&2
    exit 1
fi
if [ -x "$BT/d8" ]; then D8="$BT/d8"; else D8="$BT/d8.bat"; fi

rm -rf "$HERE/out"
mkdir -p "$HERE/out/classes" "$HERE/out/dex"

echo "javac: $(command -v javac)"
javac -source 8 -target 8 -nowarn -cp "$PLATFORM" \
      -d "$HERE/out/classes" "$HERE/$SRC" 2>&1 | grep -v "bootstrap class path" || true

# One dex only: DexClassLoader is handed a single file, so a plugin that spills
# into classes2.dex will not load. Keep plugins small.
"$D8" --min-api 24 --lib "$PLATFORM" --output "$HERE/out/dex" \
      $(find "$HERE/out/classes" -name '*.class')

echo "--- result:"
ls -la "$HERE/out/dex"
echo
echo "serve it and call the route, for example:"
echo "  python3 ../../../tools/filedrop_server.py        # serves dist/ on port 8000"
echo "  cp out/dex/classes.dex ../../../dist/HelloPlugin.dex"
echo "  curl \"http://<clock-ip>:8555/agent/dex?token=<token>&url=http://<pc-ip>:8000/HelloPlugin.dex&entry=pl.mateusz.plugin.HelloPlugin&arg=hello\""
