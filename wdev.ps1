param(
    [Parameter(Position=0)]
    [string]$Action = "deploy",

    [string]$Device = "25b41579",

    [bool]$c = $true,
    [switch]$d
)

$targetPackage = "com.insomnia.app"

if ($Action -eq "-d" -or $d) {
    # Dump cache
    if (Test-Path dump) {
        Remove-Item -Recurse -Force dump
    }
    New-Item -ItemType Directory -Path dump | Out-Null
    
    cmd.exe /c "adb -s $Device exec-out `"run-as $targetPackage sh -c 'cd cache && tar cz .'`" > dump.tar.gz"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "adb failed"
        Remove-Item dump.tar.gz -ErrorAction SilentlyContinue
        exit 1
    }
    
    tar xzf dump.tar.gz -C dump
    if ($LASTEXITCODE -ne 0) {
        Write-Host "tar extract failed"
        Remove-Item dump.tar.gz -ErrorAction SilentlyContinue
        exit 1
    }
    
    Remove-Item dump.tar.gz
    exit 0
}

if ($Action -eq "logs") {
    if ($c) {
        adb -s $Device logcat -c
    }
    Write-Host "logging..."
    adb -s $Device logcat | Where-Object { $_ -notmatch "MI-SF" } | Select-String "xxcom.insomnia"
    exit 0
}

# deploy + run + logcat
clear
./gradlew.bat assembleDebug

# Stop if build fails
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

adb -s $Device install -r app\build\outputs\apk\debug\app-debug.apk

Write-Host "launching app..."
adb -s $Device shell am start -n "$targetPackage/.MainActivity"

if ($c) {
    adb -s $Device logcat -c
}
Write-Host "logging..."
adb -s $Device logcat | Where-Object { $_ -notmatch "MI-SF" } | Select-String "xxcom.insomnia"
