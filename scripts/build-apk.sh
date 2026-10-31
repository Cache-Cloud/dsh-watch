#!/usr/bin/env bash
# 构建手表 APK（Linux / macOS 通用，也可在 GitHub Actions 里跑）。
#
# 这套脚本刻意不依赖 Gradle：
# - 手表 App 只有 9 个 Java 文件、两个资源目录，用 Gradle 反而会引入
#   AGP 下载、依赖解析、版本对齐等一大堆与业务无关的失败点；
# - 只用 Android SDK 自带的四个工具（aapt2 / javac / d8 / zipalign + apksigner）
#   就能出包，在 CI 上又快又稳，本地也是「有 JDK 就能编」。
#
# 用法：
#   scripts/build-apk.sh --sdk <Android SDK 根目录> --java-home <JDK 8+> \
#                        [--output dist] [--version-name 1.0.0] [--version-code 1]
#
# 需要的 SDK 组件：platforms/android-35（只需 android.jar）与 build-tools。
# 这两样都可由 scripts/fetch-sdk.sh 自动下载。
set -euo pipefail

WORK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
JAVA_HOME_ARG="${JAVA_HOME:-}"
BUILD_ROOT=""
OUTPUT_DIR=""
VERSION_NAME="1.0.0"
VERSION_CODE="1"
KEYSTORE="${DSH_WATCH_KEYSTORE:-}"
KEYSTORE_PASSWORD="${DSH_WATCH_KEYSTORE_PASSWORD:-dsh-watch}"
KEY_ALIAS="${DSH_WATCH_KEY_ALIAS:-dsh-watch}"
MIN_SDK="26"
TARGET_SDK="33"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sdk) SDK_ROOT="$2"; shift 2 ;;
    --java-home) JAVA_HOME_ARG="$2"; shift 2 ;;
    --build-root) BUILD_ROOT="$2"; shift 2 ;;
    --output) OUTPUT_DIR="$2"; shift 2 ;;
    --version-name) VERSION_NAME="$2"; shift 2 ;;
    --version-code) VERSION_CODE="$2"; shift 2 ;;
    --keystore) KEYSTORE="$2"; shift 2 ;;
    --keystore-password) KEYSTORE_PASSWORD="$2"; shift 2 ;;
    --key-alias) KEY_ALIAS="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

if [[ -z "$SDK_ROOT" ]]; then
  echo "请用 --sdk 或 ANDROID_HOME 指定 Android SDK 根目录" >&2
  exit 2
fi
if [[ -z "$JAVA_HOME_ARG" ]]; then
  echo "请用 --java-home 或 JAVA_HOME 指定 JDK" >&2
  exit 2
fi

JAVAC="$JAVA_HOME_ARG/bin/javac"
KEYTOOL="$JAVA_HOME_ARG/bin/keytool"
JAR_TOOL="$JAVA_HOME_ARG/bin/jar"
for tool in "$JAVAC" "$KEYTOOL" "$JAR_TOOL"; do
  [[ -x "$tool" ]] || { echo "缺少 JDK 工具：$tool" >&2; exit 2; }
done

ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
[[ -f "$ANDROID_JAR" ]] || { echo "缺少 $ANDROID_JAR" >&2; exit 2; }

# 选版本号最大的 build-tools。
BUILD_TOOLS=""
for candidate in "$SDK_ROOT"/build-tools/*; do
  [[ -d "$candidate" ]] || continue
  name="$(basename "$candidate")"
  [[ "$name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || continue
  if [[ -z "$BUILD_TOOLS" ]] || [[ "$(printf '%s\n%s\n' "$(basename "$BUILD_TOOLS")" "$name" | sort -V | tail -1)" == "$name" ]]; then
    BUILD_TOOLS="$candidate"
  fi
done
[[ -n "$BUILD_TOOLS" ]] || { echo "在 $SDK_ROOT/build-tools 下没找到 build-tools" >&2; exit 2; }

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
for tool in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
  if [[ ! -x "$tool" ]] && [[ -f "$tool" || -f "$tool.jar" ]]; then
    :
  fi
done
# apksigner 在 build-tools 里通常是脚本；d8 也可能是 .jar + 脚本，两种都试。
run_aapt2() { "$AAPT2" "$@"; }
# d8 的 class 文件是 Java 11 编的，JDK 8 跑不起来。这里优先找能跑它的 JVM：
# 先看用户给的 JAVA_HOME，再扫 /usr/lib/jvm 下的 11+，最后退回调用脚本。
find_java_for_d8() {
  for candidate in "$JAVA_HOME_ARG" /usr/lib/jvm/java-11* /usr/lib/jvm/java-17* /usr/lib/jvm/java-21*; do
    [[ -x "$candidate/bin/java" ]] || continue
    if "$candidate/bin/java" -version 2>&1 | head -1 | grep -qE '"(1\.)?(8|9|10)\.'; then
      continue
    fi
    echo "$candidate/bin/java"
    return 0
  done
  return 1
}
run_d8() {
  local java_bin=""
  java_bin="$(find_java_for_d8 || true)"
  if [[ -z "$java_bin" ]]; then
    echo "d8 需要 Java 11 及以上；请安装后重试（或改用 Android Gradle 构建）" >&2
    return 1
  fi
  "$java_bin" -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 "$@"
}
run_apksigner() {
  if [[ -x "$APKSIGNER" ]]; then "$APKSIGNER" "$@"; else
    "$JAVA_HOME_ARG/bin/java" -jar "$BUILD_TOOLS/lib/apksigner.jar" "$@"
  fi
}

if [[ -z "$BUILD_ROOT" ]]; then BUILD_ROOT="$WORK_ROOT/build"; fi
if [[ -z "$OUTPUT_DIR" ]]; then OUTPUT_DIR="$WORK_ROOT/dist"; fi
MAIN="$WORK_ROOT/app/src/main"
[[ -f "$MAIN/AndroidManifest.xml" ]] || { echo "缺少 $MAIN/AndroidManifest.xml" >&2; exit 2; }

mkdir -p "$BUILD_ROOT" "$OUTPUT_DIR"
BUILD_DIR="$(mktemp -d "$BUILD_ROOT/apk-XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT

echo "1/6 编译资源"
mkdir -p "$BUILD_DIR/flat" "$BUILD_DIR/gen"
run_aapt2 compile --dir "$MAIN/res" -o "$BUILD_DIR/flat"
mapfile -t FLATS < <(find "$BUILD_DIR/flat" -type f -name '*.flat' | sort)

echo "2/6 链接资源"
run_aapt2 link \
  -o "$BUILD_DIR/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MAIN/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  "${FLATS[@]}"

echo "3/6 编译 Java"
find "$MAIN/java" "$BUILD_DIR/gen" -type f -name '*.java' | sort > "$BUILD_DIR/sources.txt"
mkdir -p "$BUILD_DIR/classes"
# 用 -source/-target 而不是 --release：--release 会去查 JDK 自己的系统模块，
# 与 -bootclasspath android.jar 冲突。lambda 需要 build-tools 里的 core-lambda-stubs.jar
# 参与 bootclasspath（它提供 LambdaMetafactory 的 Android 版桩）。
LAMBDA_STUBS="$BUILD_TOOLS/core-lambda-stubs.jar"
BOOT_CP="$ANDROID_JAR"
if [[ -f "$LAMBDA_STUBS" ]]; then BOOT_CP="$ANDROID_JAR:$LAMBDA_STUBS"; fi
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -nowarn \
  -bootclasspath "$BOOT_CP" -classpath "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"

# 逐类核对：缺一个都会在手表上变成 NoClassDefFoundError，必须在这里挡住。
for name in MainActivity WatchUi 'WatchUi$Page' Store Api State Direct DshLink Events Ws Tls Theme CodeView Markdown; do
  [[ -f "$BUILD_DIR/classes/cn/dsh/watch/$name.class" ]] || { echo "缺少编译产物：$name.class" >&2; exit 1; }
done
echo "    关键类齐全"

echo "4/6 生成 dex"
mkdir -p "$BUILD_DIR/dex"
find "$BUILD_DIR/classes" -type f -name '*.class' | sort > "$BUILD_DIR/classes.txt"
run_d8 --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex" \
  $(cat "$BUILD_DIR/classes.txt")
[[ -f "$BUILD_DIR/dex/classes.dex" ]] || { echo "classes.dex 未生成" >&2; exit 1; }

# 用 JDK 的 jar 工具把 dex 追加进包（不依赖 zip 命令行，CI 上也一致）。
# JDK 8 的 jar 只认短参数（-uf 是 update + file）；JDK 11+ 两种都认。
(cd "$BUILD_DIR/dex" && "$JAR_TOOL" -uf "$BUILD_DIR/base.apk" classes.dex)

echo "5/6 对齐"
run_zipalign() { if [[ -x "$ZIPALIGN" ]]; then "$ZIPALIGN" "$@"; else
  "$JAVA_HOME_ARG/bin/java" -cp "$BUILD_TOOLS/lib/zipalign.jar" com.android.tools.build.tools.ZipAlign "$@"
fi; }
run_zipalign -p -f 4 "$BUILD_DIR/base.apk" "$BUILD_DIR/aligned.apk"

echo "6/6 签名"
if [[ -z "$KEYSTORE" || ! -f "$KEYSTORE" ]]; then
  KEYSTORE="$BUILD_ROOT/dsh-watch.jks"
  if [[ ! -f "$KEYSTORE" ]]; then
    "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$KEYSTORE_PASSWORD" -keypass "$KEYSTORE_PASSWORD" \
      -dname "CN=DSH Watch, O=Local, C=CN"
  fi
fi
run_apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASSWORD" \
  --key-pass "pass:$KEYSTORE_PASSWORD" --ks-key-alias "$KEY_ALIAS" \
  --v1-signing-enabled true --v2-signing-enabled true --v4-signing-enabled false \
  --out "$BUILD_DIR/signed.apk" "$BUILD_DIR/aligned.apk"

FINAL="$OUTPUT_DIR/DSH手表.apk"
cp "$BUILD_DIR/signed.apk" "$FINAL"
echo "构建成功：$FINAL（$(wc -c < "$FINAL") 字节）"
