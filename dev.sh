#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

ACTION="deploy"
PARAM2=""
CLEAR_LOGS=1
TARGET_PACKAGE="com.insomnia.app"

# Parse arguments
if [[ $# -gt 0 && ! "$1" =~ ^- ]]; then
  ACTION="$1"
  shift
fi

if [[ $# -gt 0 && ! "$1" =~ ^- && "$ACTION" == "set" ]]; then
  PARAM2="$1"
  shift
fi

for arg in "$@"; do
  if [[ "$arg" == "-c" ]]; then
    CLEAR_LOGS=1
  fi
done

DEV_DIR="$HOME/.insomnia-dev"
DEV_FILE="$DEV_DIR/device"

DEVICE=""
if [[ -f "$DEV_FILE" ]]; then
  DEVICE=$(head -n 1 "$DEV_FILE")
fi

run_adb() {
  if [[ -n "$DEVICE" ]]; then
    adb -s "$DEVICE" "$@"
  else
    adb "$@"
  fi
}

detect_single_device() {
  local devices
  devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  local count
  count=$(echo "$devices" | sed '/^$/d' | wc -l | tr -d ' ')

  if [[ "$count" -eq 1 ]]; then
    echo "$devices" | sed '/^$/d' | head -n 1
    return 0
  fi

  return 1
}

stream_logs() {
  if [[ "$CLEAR_LOGS" -eq 1 ]]; then
    run_adb logcat -c
  fi
  echo "logging..."
  clear
  run_adb logcat | grep -v "MI-SF" | grep "xxcom.insomnia"
}

if [[ "$ACTION" == "ls" ]]; then
  adb devices
  exit 0
fi

if [[ "$ACTION" == "emu" ]]; then
  EMU="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/emulator/emulator"
  [[ -x "$EMU" ]] || EMU="emulator"
  pkill -f 'qemu-system|emulator.*-avd' 2>/dev/null || true
  AVD="$("$EMU" -list-avds 2>/dev/null | grep -iE 'tv|television' | head -1)"
  if [[ -z "$AVD" ]]; then
    echo "No TV AVD found."
    exit 1
  fi
  echo "Starting $AVD"
  nohup "$EMU" -avd "$AVD" -no-snapshot-load -no-snapshot-save >/tmp/insomnia-emulator.log 2>&1 &
  disown
  exit 0
fi

if [[ "$ACTION" == "set" ]]; then
  if [[ -z "$PARAM2" ]]; then
    if ! PARAM2=$(detect_single_device); then
      echo "Please provide a device ID: ./dev.sh set <device_id>"
      echo "Tip: if exactly one device is connected, ./dev.sh set works without args."
      exit 1
    fi
  fi
  mkdir -p "$DEV_DIR"
  echo "$PARAM2" > "$DEV_FILE"
  echo "Set default device to $PARAM2"
  exit 0
fi

if [[ "$ACTION" == "dump" ]]; then
  rm -rf dump
  mkdir dump
  TAR="$ROOT/dump.tar.gz"

  set +e
  run_adb exec-out \
    "run-as $TARGET_PACKAGE sh -c 'cd cache/providers && tar cz .'" \
    > "$TAR"
  if [ "$?" -ne 0 ]; then
    echo "adb failed"
    rm -f "$TAR"
    exit 1
  fi

  tar xzf "$TAR" -C dump
  if [ "$?" -ne 0 ]; then
    echo "tar extract failed"
    rm -f "$TAR"
    exit 1
  fi
  set -e
  
  rm -f "$TAR"
  echo "Extracted to $ROOT/dump/"
  exit 0
fi

if [[ "$ACTION" == "logs" ]]; then
  stream_logs
  exit 0
fi

if [[ "$ACTION" == "deploy" ]]; then
  echo "Building TS providers..."
  (cd providers-ts && yarn build)
  
  echo "Building APK..."
  ./gradlew :app:assembleDebug
  
  echo "Installing APK..."
  run_adb install -r app/build/outputs/apk/debug/app-debug.apk
  
  echo "launching app..."
  run_adb shell am start -n "$TARGET_PACKAGE/.MainActivity"
  
  stream_logs
  exit 0
fi

echo "Unknown action: $ACTION"
echo "Usage: ./dev.sh [emu|deploy|logs|dump|ls|set [device_id]] [-c]"
exit 1

