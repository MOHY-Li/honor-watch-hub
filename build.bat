@echo off
chcp 65001 >nul
setlocal
set ROOT=%~dp0.
cd /d "%ROOT%\android"
set JAVA_HOME=%ROOT%\.toolchain\jdk\jdk-17.0.2
set ANDROID_HOME=%ROOT%\.toolchain\android-sdk
set GRADLE_USER_HOME=%ROOT%\.toolchain\gradle-home

echo ========== ???? ==========
call gradlew.bat :app:testDebugUnitTest --no-daemon
if errorlevel 1 goto fail

echo.
echo ========== ?? APK ==========
call gradlew.bat :app:assembleDebug --no-daemon
if errorlevel 1 goto fail

if not exist "%ROOT%\dist" mkdir "%ROOT%\dist"
copy /y "app\build\outputs\apk\debug\app-debug.apk" "%ROOT%\dist\WatchHub.apk" >nul

echo.
echo ?????APK: dist\WatchHub.apk
exit /b 0

:fail
echo.
echo ???????????
exit /b 1
