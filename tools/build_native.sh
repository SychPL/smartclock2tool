#!/usr/bin/env bash
# Canonical cross-build for the ARM32 probes (MT8167, 32-bit Android).
#
# Toolchain note: historical runs were built with an older NDK gcc 4.9
# toolchain. Its wrapper is a Linux ELF binary and does not run on Windows, so
# the canonical path is now clang from the Android NDK. The only difference
# between gcc 4.9 and clang is codegen: struct layout, markers and offsets
# (everything that matters for the results) are determined by the source, not by
# the compiler.
#
# MM_BUILD_ID is baked into the binary (git rev-parse HEAD of the source), so on
# the device you can tell which commit a build came from.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

# NDK autodetection (order: NDK_ROOT, then the usual install locations).
NDK="${NDK_ROOT:-${NDK:-}}"
if [[ -z "${NDK}" || ! -d "${NDK}" ]]; then
  for cand in \
    "/c/Users/${USER:-}/AppData/Local/Android/Sdk/ndk/27.1.12297006" \
    "$LOCALAPPDATA/Android/Sdk/ndk/27.1.12297006" \
    "/c/Android/Sdk/ndk/27.1.12297006"; do
    if [[ -d "${cand}" ]]; then NDK="${cand}"; break; fi
  done
fi
if [[ -z "${NDK}" || ! -d "${NDK}" ]]; then
  echo "FATAL: no NDK. Set NDK_ROOT or install the NDK (tested: 27.1.12297006)." >&2
  exit 1
fi

CLANG="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
SYSROOT="$NDK/toolchains/llvm/prebuilt/windows-x86_64/sysroot"
if [[ ! -x "$CLANG" ]]; then
  echo "FATAL: no clang in $NDK" >&2
  exit 1
fi

BUILD_ID="$(cd "$ROOT" && git rev-parse --short HEAD 2>/dev/null || echo nogit)"
echo "build_id=${BUILD_ID} ndk=$(basename "$NDK")"

# Builds one source -> binary pair. $1 = source (relative to the repo root),
# $2 = output. MM_BUILD_ID carries the short commit hash plus a "-dirty" marker
# when the source differs from the committed version: without that marker a
# binary could keep an unchanged build id while containing uncommitted edits,
# which is exactly what this identifier exists to prevent (that happened once
# during development and cost a day of confusing measurements).
build_one() {
  local src="$1" out="$2"
  local id="${BUILD_ID}"
  if ! ( cd "$ROOT" && git diff --quiet -- "$src" ) \
     || ! ( cd "$ROOT" && git diff --quiet --cached -- "$src" ); then
    id="${BUILD_ID}-dirty"
  fi
  echo "building ${src} build_id=${id}"
  ( cd "$ROOT" && "$CLANG" \
      --target=armv7a-linux-androideabi24 \
      --sysroot="$SYSROOT" \
      -O2 -pie -fno-stack-protector \
      -DMM_BUILD_ID="\"${id}\"" \
      -o "$out" "$src" )
  ( cd "$ROOT" && sha256sum "$out" )
}

build_one exploits/pvr_mmap_oob_probe.c exploits/pvr_mmap_oob_probe
build_one exploits/pvr_bridge_fuzz.c     exploits/pvr_bridge_fuzz
build_one exploits/clockroot.c           exploits/clockroot
build_one exploits/binder_probe.c          exploits/binder_probe
build_one exploits/proparea.c             exploits/proparea
