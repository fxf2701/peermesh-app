#!/usr/bin/env bash
set -euo pipefail

AVD_NAME="${1:-}"

usage() {
  cat <<'USAGE'
Usage:
  start_emulator.sh Pixel34
  start_emulator.sh Pixel26

Optional env:
  ANDROID_SDK_ROOT   # if emulator not in PATH
USAGE
}

if [[ -z "$AVD_NAME" ]]; then
  usage
  exit 1
fi

if [[ "$AVD_NAME" != "Pixel34" && "$AVD_NAME" != "Pixel26" ]]; then
  echo "Unknown AVD: $AVD_NAME"
  usage
  exit 1
fi

EMU_BIN=""
if [[ -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/emulator/emulator" ]]; then
  EMU_BIN="$ANDROID_SDK_ROOT/emulator/emulator"
elif command -v emulator >/dev/null 2>&1; then
  EMU_BIN="emulator"
else
  echo "emulator not found. Set ANDROID_SDK_ROOT or add SDK emulator to PATH."
  exit 1
fi

"$EMU_BIN" -avd "$AVD_NAME"
