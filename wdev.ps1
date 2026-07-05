param(
    [Parameter(Position=0)]
    [string]$Action = "deploy",

    [Parameter(Position=1)]
    [string]$Param2 = "",

    [switch]$c = $true
)

$targetPackage = "com.insomnia.app"

$devDir = Join-Path $HOME ".insomnia-dev"
$devFile = Join-Path $devDir "device"

$Device = "25b41579"
if (Test-Path $devFile) {
    $Device = Get-Content $devFile | Select-Object -First 1
}

clear
Write-Host "device: $Device"

function Run-Adb {
    param(
        [Parameter(ValueFromRemainingArguments=$true)]
        $AdbArgs
    )
    if (-not [string]::IsNullOrWhiteSpace($Device)) {
        & adb -s $Device @AdbArgs
    } else {
        & adb @AdbArgs
    }
}

function Stream-Logs {
    if ($c) {
        Run-Adb logcat -c
    }
    Write-Host "logging..."
    if (-not [string]::IsNullOrWhiteSpace($Device)) {
        adb -s $Device logcat | Where-Object { $_ -notmatch "MI-SF" } | Select-String "xxcom.insomnia"
    } else {
        adb logcat | Where-Object { $_ -notmatch "MI-SF" } | Select-String "xxcom.insomnia"
    }
}

if ($Action -eq "ls") {
    adb devices
    exit 0
}

if ($Action -eq "set") {
    if ([string]::IsNullOrWhiteSpace($Param2)) {
        Write-Host "Please provide a device ID."
        exit 1
    }
    if (-not (Test-Path $devDir)) {
        New-Item -ItemType Directory -Path $devDir | Out-Null
    }
    Out-File -FilePath $devFile -InputObject $Param2 -Encoding ASCII
    Write-Host "Set default device to $Param2"
    exit 0
}

if ($Action -eq "dump") {
    if (Test-Path dump) {
        Remove-Item -Recurse -Force dump
    }
    New-Item -ItemType Directory -Path dump | Out-Null
    
    $adbCmd = if ([string]::IsNullOrWhiteSpace($Device)) { "adb" } else { "adb -s $Device" }
    
    cmd.exe /c "$adbCmd exec-out `"run-as $targetPackage sh -c 'cd cache && tar cz .'`" > dump.tar.gz"
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
    Stream-Logs
    exit 0
}

if ($Action -eq "deploy") {
    ./gradlew.bat assembleDebug

    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    Run-Adb install -r app\build\outputs\apk\debug\app-debug.apk

    Write-Host "launching app..."
    Run-Adb shell am start -n "$targetPackage/.MainActivity"

    Stream-Logs
    exit 0
}

Write-Host "Unknown action: $Action"
exit 1
