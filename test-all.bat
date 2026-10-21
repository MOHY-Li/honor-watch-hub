@echo off
chcp 65001 >nul
setlocal
set ROOT=%~dp0.
cd /d "%ROOT%"

echo ========== Python server tests ==========
set PYTHONIOENCODING=utf-8
python server\tests\run_all.py
if errorlevel 1 goto fail

echo.
echo ========== Android unit + protocol tests ==========
cd android
set JAVA_HOME=%ROOT%\.toolchain\jdk\jdk-17.0.2
set ANDROID_HOME=%ROOT%\.toolchain\android-sdk
set GRADLE_USER_HOME=%ROOT%\.toolchain\gradle-home
call gradlew.bat :app:testDebugUnitTest --no-daemon
if errorlevel 1 goto fail

echo.
echo ========== Android build APK ==========
call gradlew.bat :app:assembleDebug --no-daemon
if errorlevel 1 goto fail

echo.
echo ALL PASSED. APK: android\app\build\outputs\apk\debug\app-debug.apk
exit /b 0

:fail
echo.
echo TESTS FAILED, see output above.
exit /b 1
