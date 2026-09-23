@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0\.."

set VULKAN_HEADERS_URL=https://github.com/KhronosGroup/Vulkan-Headers.git
set DLSS_URL=https://github.com/NVIDIA/DLSS.git

if "%~1"=="vendor" goto :vendor
if "%~1"=="run" goto :run
echo usage: %~nx0 {vendor [--force] ^| run [--loader neoforge^|fabric]}
exit /b 1

:vendor
set FORCE=0
if "%~2"=="--force" set FORCE=1
call :vendor_clone "%VULKAN_HEADERS_URL%" "third_party\vulkan-headers" %FORCE%
call :vendor_clone "%DLSS_URL%" "third_party\dlss" %FORCE%
echo [vendor] done
exit /b 0

:vendor_clone
set "URL=%~1"
set "DEST=%~2"
set "FORCE_FLAG=%~3"
if exist "%DEST%" (
    dir /a /b "%DEST%" >nul 2>&1
    if not errorlevel 1 (
        if "%FORCE_FLAG%"=="0" (
            echo [vendor] %DEST% already populated, skipping ^(pass --force to re-clone^)
            exit /b 0
        )
    )
)
if exist "%DEST%" rmdir /s /q "%DEST%"
echo [vendor] cloning %URL% -^> %DEST%
git clone --depth 1 "%URL%" "%DEST%"
rem Strip the inner .git so this is a plain vendored tree, not a nested
rem repo/gitlink - third_party\dlss and third_party\vulkan-headers are
rem gitignored and managed by this script, not by git submodules.
rmdir /s /q "%DEST%\.git"
exit /b 0

:run
set LOADER=neoforge
if "%~2"=="--loader" set LOADER=%~3

set MISSING=
if not exist "third_party\vulkan-headers\*" set MISSING=!MISSING! third_party\vulkan-headers
if not exist "third_party\dlss\*" set MISSING=!MISSING! third_party\dlss
if not "!MISSING!"=="" (
    echo [run] missing:!MISSING!
    echo [run] run %~nx0 vendor first
    exit /b 1
)

echo [run] detected windows, launching %LOADER%: gradlew.bat :%LOADER%:runClient
call gradlew.bat ":%LOADER%:runClient"
exit /b %errorlevel%
