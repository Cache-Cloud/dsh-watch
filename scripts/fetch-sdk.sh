#!/usr/bin/env bash
# 下载构建 APK 所需的最小 Android SDK 组件。
#
# 只要两个东西：
#   platforms/android-35/android.jar   —— javac 的编译目标（Android 35 的 API）
#   build-tools/<ver>/                 —— aapt2 / d8 / zipalign / apksigner
# 合计约 330 MB，比完整的 SDK 小得多，CI 上通常 1 分钟内跑完。
#
# 用法：
#   scripts/fetch-sdk.sh --dest <目录> [--platform 35] [--build-tools 34.0.0]
#
# 国内网络下的镜像：默认走腾讯云镜像（Google 源被墙时可用）；
# 设置 ANDROID_SDK_MIRROR 可换，例如 https://mirrors.cloud.tencent.com/AndroidSDK
set -euo pipefail

DEST="${ANDROID_HOME:-}"
PLATFORM="35"
BUILD_TOOLS="34.0.0"
MIRROR="${ANDROID_SDK_MIRROR:-https://mirrors.cloud.tencent.com/AndroidSDK}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dest) DEST="$2"; shift 2 ;;
    --platform) PLATFORM="$2"; shift 2 ;;
    --build-tools) BUILD_TOOLS="$2"; shift 2 ;;
    --mirror) MIRROR="$2"; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

[[ -n "$DEST" ]] || { echo "请用 --dest 或 ANDROID_HOME 指定目标目录" >&2; exit 2; }
mkdir -p "$DEST"

say() { echo "[sdk] $*"; }

# ---------------------------------------------------------------- build-tools
BT_DIR="$DEST/build-tools/$BUILD_TOOLS"
if [[ -x "$BT_DIR/aapt2" && -x "$BT_DIR/apksigner" ]]; then
  say "build-tools $BUILD_TOOLS 已存在，跳过"
else
  say "下载 build-tools $BUILD_TOOLS"
  ZIP="$DEST/.bt.zip"
  # 镜像里的命名只带主版本号（34.0.0 → r34），不能直接拼完整版本。
  BT_MAJOR="${BUILD_TOOLS%%.*}"
  curl -fsSL --retry 3 --connect-timeout 20 -o "$ZIP" \
    "$MIRROR/build-tools_r${BT_MAJOR}-linux.zip"
  # 解压出的目录名是 android-14 之类，不能假定；用 JDK 的 jar 解（不依赖 unzip）。
  TMP="$DEST/.bt-unpack"
  rm -rf "$TMP"; mkdir -p "$TMP"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ZIP" -d "$TMP"
  else
    (cd "$TMP" && jar xf "$ZIP")
  fi
  SRC="$(find "$TMP" -maxdepth 1 -mindepth 1 -type d | head -1)"
  [[ -n "$SRC" ]] || { echo "解压 build-tools 失败" >&2; exit 1; }
  mkdir -p "$(dirname "$BT_DIR")"
  rm -rf "$BT_DIR"
  mv "$SRC" "$BT_DIR"
  chmod +x "$BT_DIR"/aapt2 "$BT_DIR"/d8 "$BT_DIR"/zipalign "$BT_DIR"/apksigner 2>/dev/null || true
  rm -rf "$ZIP" "$TMP"
fi

# ---------------------------------------------------------------- android.jar
AJ="$DEST/platforms/android-$PLATFORM/android.jar"
if [[ -f "$AJ" ]]; then
  say "platforms/android-$PLATFORM 已存在，跳过"
else
  say "下载 platforms/android-$PLATFORM/android.jar"
  mkdir -p "$(dirname "$AJ")"
  # Google 仓库的 zip 太大（含源与布局文件）；android.jar 单文件在社区镜像里有，
  # 但更稳的是直接取 Google 的 platform zip 再挑出 android.jar。
  PLAT_ZIP="$DEST/.platform.zip"
  # 各平台的可下载修订号不同（35 最新是 r02，34 是 r03…），按顺序试。
  PLAT_OK=""
  for rev in r02 r03 r01; do
    for origin in "https://dl.google.com/android/repository" "$MIRROR"; do
      url="$origin/platform-${PLATFORM}_${rev}.zip"
      if curl -fsSL --retry 2 --connect-timeout 20 -o "$PLAT_ZIP" "$url" 2>/dev/null; then
        say "取到 platform-${PLATFORM}_${rev}.zip（$origin）"
        PLAT_OK="$url"
        break 2
      fi
    done
  done
  if [[ -z "$PLAT_OK" ]]; then
    say "Google 源不可用，改用社区镜像（Sable/android-platforms）"
    curl -fsSL --retry 3 --connect-timeout 20 -o "$AJ" \
      "https://raw.githubusercontent.com/Sable/android-platforms/master/android-${PLATFORM}/android.jar"
  else
    TMP="$DEST/.plat-unpack"
    rm -rf "$TMP"; mkdir -p "$TMP"
    if command -v unzip >/dev/null 2>&1; then
      unzip -q "$PLAT_ZIP" "*/android.jar" -d "$TMP"
    else
      (cd "$TMP" && jar xf "$PLAT_ZIP")
    fi
    FOUND="$(find "$TMP" -name android.jar -type f | head -1)"
    [[ -n "$FOUND" ]] || { echo "platform zip 里没有 android.jar" >&2; exit 1; }
    cp "$FOUND" "$AJ"
    rm -rf "$PLAT_ZIP" "$TMP"
  fi
fi

say "SDK 就绪：$DEST"
ls -1 "$DEST/build-tools" "$DEST/platforms" 2>/dev/null || true
