param([switch]$NoBrowser)

$ErrorActionPreference = 'Stop'
$appUrl = 'http://127.0.0.1:8765'
$appJar = Join-Path $PSScriptRoot 'song-metadata-studio-1.0.0.jar'
$logDirectory = Join-Path $PSScriptRoot 'logs'

function Find-Chrome {
    $command = Get-Command chrome.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Google\Chrome\Application\chrome.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Google\Chrome\Application\chrome.exe'),
        (Join-Path $env:LOCALAPPDATA 'Google\Chrome\Application\chrome.exe')
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    return $null
}

function Test-SongMetadataStudio([int]$TimeoutSeconds = 2) {
    try {
        $health = Invoke-RestMethod -Uri "$appUrl/api/health" -TimeoutSec $TimeoutSeconds
        return $health.status -eq 'ok' -and
            ($health.PSObject.Properties.Name -contains 'ytmusicAvailable')
    } catch {
        return $false
    }
}

function Open-AppBrowser {
    if ($NoBrowser) { return }
    $chrome = Find-Chrome
    if ($chrome) {
        Start-Process -FilePath $chrome -ArgumentList @("--app=$appUrl") | Out-Null
        return
    }
    Start-Process -FilePath $appUrl | Out-Null
    Show-LauncherMessage 'Chrome was not found, so Song Metadata Studio opened in your default browser.' `
        'Chrome not found' 'Warning'
}

function Show-LauncherMessage([string]$Message, [string]$Title, [string]$Icon) {
    try {
        Add-Type -AssemblyName System.Windows.Forms
        $iconValue = [System.Enum]::Parse([System.Windows.Forms.MessageBoxIcon], $Icon)
        [System.Windows.Forms.MessageBox]::Show(
            $Message, $Title, [System.Windows.Forms.MessageBoxButtons]::OK,
            $iconValue) | Out-Null
    } catch {
        Write-Host $Message
    }
}

$serverProcess = $null
try {
    if (-not (Test-Path -LiteralPath $appJar -PathType Leaf)) {
        throw "The installed app JAR is missing: $appJar. Run Install-SongMetadataStudio.cmd again."
    }
    if (Test-SongMetadataStudio) {
        Open-AppBrowser
        exit 0
    }

    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if (-not $java) {
        throw 'Java 21 or newer was not found on PATH. Install Java 21, then double-click the app again.'
    }

    $listener = Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)" `
            -ErrorAction SilentlyContinue
        $owner = if ($process) { "$($process.Name) (PID $($process.ProcessId))" } else { 'another process' }
        throw "Port 8765 is already in use by $owner, but it is not Song Metadata Studio. Close that app or process and try again."
    }

    New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $stdout = Join-Path $logDirectory "server-$stamp.out.log"
    $stderr = Join-Path $logDirectory "server-$stamp.err.log"
    $arguments = @(
        '-jar', ('"{0}"' -f $appJar),
        '--server.address=127.0.0.1',
        '--server.port=8765'
    )
    $serverProcess = Start-Process -FilePath $java.Source -ArgumentList $arguments `
        -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr

    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($serverProcess.HasExited) { break }
        if (Test-SongMetadataStudio -TimeoutSeconds 2) {
            $ready = $true
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) {
        $serverProcess.Refresh()
        if (-not $serverProcess.HasExited) {
            Stop-Process -Id $serverProcess.Id -Force
        }
        $tail = @()
        foreach ($log in @($stdout, $stderr)) {
            if (Test-Path -LiteralPath $log) {
                $tail += Get-Content -LiteralPath $log -Tail 12
            }
        }
        $detail = if ($tail.Count) { "`r`n`r`n$($tail -join "`r`n")" } else { '' }
        throw "Song Metadata Studio did not start on 127.0.0.1:8765. Check the Java 21, FFmpeg/FFprobe, and Python setup. Logs: $logDirectory$detail"
    }

    Open-AppBrowser
} catch {
    Show-LauncherMessage $_.Exception.Message 'Song Metadata Studio could not start' 'Error'
    exit 1
}
