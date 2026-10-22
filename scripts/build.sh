#!/usr/bin/env bash
# WatchHub 2.0 本地构建：跑单测 → 打 debug APK → 复制到 dist/WatchHub.apk
#
# 用法：scripts/build.sh [--skip-tests]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKIP_TESTS=0
[[ "${1:-}" == "--skip-tests" ]] && SKIP_TESTS=1

# JDK：优先环境变量，其次 Android Studio 自带 JBR，最后尝试系统 java
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
    JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  fi
fi
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "找不到 JDK。请设置 JAVA_HOME（需要 17+）。" >&2
  exit 1
fi
export JAVA_HOME

# Android SDK
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/usr/local/lib/android/sdk"; do
    if [[ -d "$candidate" ]]; then
      ANDROID_HOME="$candidate"
      break
    fi
  done
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "找不到 Android SDK。请设置 ANDROID_HOME。" >&2
  exit 1
fi

cd "$ROOT/android"
echo "sdk.dir=$ANDROID_HOME" > local.properties
chmod +x gradlew

echo "== JDK: $JAVA_HOME"
echo "== SDK: $ANDROID_HOME"

if [[ "$SKIP_TESTS" -eq 0 ]]; then
  echo "== 单元测试"
  ./gradlew :app:testDebugUnitTest --no-daemon
fi

echo "== 构建 debug APK"
./gradlew :app:assembleDebug --no-daemon

mkdir -p "$ROOT/dist"
cp app/build/outputs/apk/debug/app-debug.apk "$ROOT/dist/WatchHub.apk"
echo "== 产物: $ROOT/dist/WatchHub.apk"
