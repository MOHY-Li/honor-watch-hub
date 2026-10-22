@echo off
chcp 65001 >nul
setlocal
set ROOT=%~dp0..
cd /d "%ROOT%\android"

rem JDK：优先 JAVA_HOME，其次仓库内 .toolchain\jdk
if "%JAVA_HOME%"=="" (
  if exist "%ROOT%\.toolchain\jdk" set JAVA_HOME=%ROOT%\.toolchain\jdk
)
rem Android SDK：优先 ANDROID_HOME，其次 .toolchain\android-sdk
if "%ANDROID_HOME%"=="" (
  if exist "%ROOT%\.toolchain\android-sdk" set ANDROID_HOME=%ROOT%\.toolchain\android-sdk
)

if "%JAVA_HOME%"=="" (
  echo 找不到 JDK，请设置 JAVA_HOME（需要 17+）
  exit /b 1
)
if "%ANDROID_HOME%"=="" (
  echo 找不到 Android SDK，请设置 ANDROID_HOME
  exit /b 1
)
echo sdk.dir=%ANDROID_HOME:\=/%> local.properties

echo ========== 单元测试 ==========
call gradlew.bat :app:testDebugUnitTest --no-daemon
if errorlevel 1 goto fail

echo.
echo ========== 构建 APK ==========
call gradlew.bat :app:assembleDebug --no-daemon
if errorlevel 1 goto fail

if not exist "%ROOT%\dist" mkdir "%ROOT%\dist"
copy /y "app\build\outputs\apk\debug\app-debug.apk" "%ROOT%\dist\WatchHub.apk" >nul

echo.
echo 产物: dist\WatchHub.apk
exit /b 0

:fail
echo.
echo 构建失败
exit /b 1
