@echo off
REM === AI Bookkeeper APK Installer ===
REM 
REM Usage:
REM   install-apk.bat                    -- Install via USB
REM   install-apk.bat 192.168.123.12     -- Install via WiFi (default port 5555)  
REM   install-apk.bat 192.168.123.12:38247 -- Install via WiFi (custom port)
REM
REM Prerequisites:
REM   - Phone: Enable Developer Options + USB Debugging (or Wireless Debugging)
REM   - USB: Connect phone via USB cable
REM   - WiFi: Phone and PC on same network, enable Wireless Debugging on phone

set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
set PATH=%ANDROID_HOME%\platform-tools;%PATH%
set APK=app\build\outputs\apk\debug\app-debug.apk

echo.
echo === AI Bookkeeper APK Installer ===
echo.

if not exist "%APK%" (
    echo ERROR: APK not found at %APK%
    echo Run: gradlew.bat assembleDebug
    exit /b 1
)

if "%~1"=="" (
    echo Mode: USB
) else (
    echo Mode: WiFi - Connecting to %~1 ...
    adb connect %~1
    timeout /t 3 >nul
)

echo.
echo Connected devices:
adb devices -l
echo.

echo Installing %APK% ...
adb install -r "%APK%"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo SUCCESS! Launching app...
    adb shell am start -n com.aibookkeeper/.MainActivity
    echo.
    echo App installed and launched!
) else (
    echo.
    echo FAILED. Check:
    echo   1. Phone has USB/Wireless debugging enabled
    echo   2. Accept the "Allow USB debugging" prompt on phone
    echo   3. For WiFi: run "adb pair IP:PORT" first if using Android 11+
)
