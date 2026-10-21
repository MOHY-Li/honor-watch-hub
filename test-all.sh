#!/usr/bin/env bash
# 一次跑完 Python 端 + Android 端全部测试
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "========== Python: 服务端 =========="
PYTHONIOENCODING=utf-8 python3 server/tests/run_all.py

echo
echo "========== Android: 单元 + 协议一致性 =========="
if [ -d "$ROOT/.toolchain/jdk" ]; then
  export JAVA_HOME="$(echo "$ROOT"/.toolchain/jdk/*)"
  export ANDROID_HOME="$ROOT/.toolchain/android-sdk"
  export GRADLE_USER_HOME="$ROOT/.toolchain/gradle-home"
fi
cd android
./gradlew :app:testDebugUnitTest --no-daemon

echo
echo "========== Android: 构建 APK =========="
./gradlew :app:assembleDebug --no-daemon
echo "全部通过。APK: android/app/build/outputs/apk/debug/app-debug.apk"