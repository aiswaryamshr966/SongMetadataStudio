param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$InstallDirectory = (Join-Path $env:LOCALAPPDATA 'Programs\SongMetadataStudio'),
    [switch]$NoShortcut,
    [switch]$Silent
)

$ErrorActionPreference = 'Stop'

function Show-InstallerMessage([string]$Message, [string]$Title, [string]$Icon) {
    if ($Silent) {
        Write-Host $Message
        return
    }
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

function Find-Chrome {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Google\Chrome\Application\chrome.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Google\Chrome\Application\chrome.exe'),
        (Join-Path $env:LOCALAPPDATA 'Google\Chrome\Application\chrome.exe')
    )
    $command = Get-Command chrome.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    return $null
}

try {
    $repository = (Resolve-Path -LiteralPath $RepositoryRoot).Path
    $sourceJar = Join-Path $repository 'target\song-metadata-studio-1.0.0.jar'
    if (-not (Test-Path -LiteralPath $sourceJar -PathType Leaf)) {
        if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) {
            throw 'The packaged app is missing and Maven is unavailable. Build with Maven 3.9+ or install it before running setup.'
        }
        Push-Location $repository
        try {
            & mvn.cmd clean package
            if ($LASTEXITCODE -ne 0) {
                throw "Maven could not build the app (exit code $LASTEXITCODE)."
            }
        } finally {
            Pop-Location
        }
    }

    $target = [System.IO.Path]::GetFullPath($InstallDirectory)
    $liveListener = Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($liveListener) {
        $liveProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($liveListener.OwningProcess)" `
            -ErrorAction SilentlyContinue
        $installedJar = Join-Path $target 'song-metadata-studio-1.0.0.jar'
        if ($liveProcess -and $liveProcess.CommandLine -and
                $liveProcess.CommandLine.IndexOf($installedJar, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw 'Song Metadata Studio is running from the install folder. Shut it down from the app page, then run setup again.'
        }
    }

    New-Item -ItemType Directory -Force -Path $target | Out-Null
    $temporaryJar = Join-Path $target 'song-metadata-studio-1.0.0.jar.new'
    try {
        Copy-Item -LiteralPath $sourceJar -Destination $temporaryJar -Force
        Move-Item -LiteralPath $temporaryJar -Destination (Join-Path $target 'song-metadata-studio-1.0.0.jar') -Force
    } finally {
        if (Test-Path -LiteralPath $temporaryJar) {
            Remove-Item -LiteralPath $temporaryJar -Force
        }
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'launch-windows.ps1') `
        -Destination (Join-Path $target 'launch-windows.ps1') -Force

    $shortcutPath = $null
    if (-not $NoShortcut) {
        $desktop = [Environment]::GetFolderPath('Desktop')
        $shortcutPath = Join-Path $desktop 'Song Metadata Studio.lnk'
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut($shortcutPath)
        $shortcut.TargetPath = Join-Path $PSHOME 'powershell.exe'
        $shortcut.Arguments = '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' +
            (Join-Path $target 'launch-windows.ps1') + '"'
        $shortcut.WorkingDirectory = $target
        $chrome = Find-Chrome
        if ($chrome) {
            $shortcut.IconLocation = "$chrome,0"
        }
        $shortcut.Description = 'Start the local-only Song Metadata Studio and open it in Chrome'
        $shortcut.Save()
    }

    $message = "Song Metadata Studio is installed in:`r`n$target"
    if ($shortcutPath) {
        $message += "`r`n`r`nDouble-click the 'Song Metadata Studio' shortcut on your Desktop to start it."
    } else {
        $message += "`r`n`r`nRun launch-windows.ps1 to start the app."
    }
    Show-InstallerMessage $message 'Song Metadata Studio setup' 'Information'
} catch {
    Show-InstallerMessage $_.Exception.Message 'Song Metadata Studio setup failed' 'Error'
    exit 1
}
