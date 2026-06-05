#!/bin/bash
cd providers-ts && yarn build && cd -
./gradlew :app:assembleDebug 2>&1 | tail -5 && adb install -r app/build/outputs/apk/debug/app-debug.apk

