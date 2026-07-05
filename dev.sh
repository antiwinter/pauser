#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

ACTION="deploy"
PARAM2=""
CLEAR_LOGS=0

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

stream_logs() {
  if [[ "$CLEAR_LOGS" -eq 1 ]]; then
    run_adb logcat -c
  fi
  echo "logging..."
  run_adb logcat | grep -v "MI-SF" | grep "com.insomnia"
}

if [[ "$ACTION" == "ls" ]]; then
  adb devices
  exit 0
fi

if [[ "$ACTION" == "set" ]]; then
  if [[ -z "$PARAM2" ]]; then
    echo "Please provide a device ID."
    exit 1
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
    "run-as com.insomnia.app sh -c 'cd cache && tar cz .'" \
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
  clear
  echo "Building TS providers..."
  (cd providers-ts && yarn build)
  
  echo "Building APK..."
  ./gradlew :app:assembleDebug 2>&1 | tail -5
  
  echo "Installing APK..."
  run_adb install -r app/build/outputs/apk/debug/app-debug.apk
  
  echo "launching app..."
  run_adb shell am start -n "com.insomnia.app/.MainActivity"
  
  stream_logs
  exit 0
fi

echo "Unknown action: $ACTION"
echo "Usage: ./dev.sh [deploy|logs|dump|ls|set <device_id>] [-c]"
exit 1

