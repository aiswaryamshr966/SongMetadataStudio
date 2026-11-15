@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install-windows.ps1" -RepositoryRoot "%~dp0"
if errorlevel 1 (
    echo Song Metadata Studio setup did not complete.
    pause
)
