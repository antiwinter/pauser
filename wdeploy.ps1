param(
    [string]$Device = "25b41579"
)

clear
./gradlew.bat assembleDebug
adb -s $Device install -r app\build\outputs\apk\debug\app-debug.apk
echo logging...
adb -s $Device logcat -c
adb -s $Device logcat | Where-Object { $_ -notmatch "MI-SF" } | select-string "xxcom.insomnia"