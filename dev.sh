#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

ACTION="deploy"

# Parse optional action
if [[ $# -gt 0 && ! "$1" =~ ^- ]]; then
  ACTION="$1"
  shift
elif [[ $# -gt 0 && "$1" == "-d" ]]; then
  ACTION="dump"
  shift
fi

# Determine device serial arguments if passed
ADB_ARGS=()
if [[ $# -gt 0 ]]; then
  ADB_ARGS=(-s "$1")
elif [[ -n "${ADB_SERIAL:-}" ]]; then
  ADB_ARGS=(-s "$ADB_SERIAL")
fi

run_adb() {
  if ((${#ADB_ARGS[@]} > 0)); then
    adb "${ADB_ARGS[@]}" "$@"
  else
    adb "$@"
  fi
}

if [[ "$ACTION" == "dump" ]]; then
  rm -rf dump
  mkdir dump
  TAR="$ROOT/dump.tar.gz"
  
  set +e
  run_adb exec-out \
    "run-as com.insomnia.app sh -c 'cd cache && tar cz dump'" \
    > "$TAR"
  if [ "$?" -ne 0 ]; then
    echo "adb failed"
    rm -f "$TAR"
    exit 1
  fi
  
  tar xzf "$TAR" -C dump --strip-components=1
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
  run_adb logcat -c
  run_adb logcat | grep com.insomnia
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
  
  echo "Launching app..."
  run_adb shell am start -n "com.insomnia.app/.MainActivity"
  
  echo "Logging..."
  run_adb logcat -c
  run_adb logcat | grep com.insomnia
  exit 0
fi

echo "Unknown action: $ACTION"
echo "Usage: ./deploy.sh [deploy|logs|dump|-d] [device_serial]"
exit 1

