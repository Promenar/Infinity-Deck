#!/usr/bin/env bash
# 为 Android arm64-v8a 交叉编译精简共享库版 FFmpeg + ffprobe（含 8-bit libx264），
# 所有输出符合 16KB 页对齐，且不在 FFMPEG_CONFIGURATION 或二进制段内嵌入构建机绝对路径。
set -euo pipefail

FFMPEG_ARCHIVE=""
X264_ARCHIVE=""
NDK_DIR=""
API="31"
OUTPUT_DIR=""
WORK_DIR=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --ffmpeg-archive) FFMPEG_ARCHIVE="$2"; shift 2 ;;
    --x264-archive) X264_ARCHIVE="$2"; shift 2 ;;
    --ndk-dir) NDK_DIR="$2"; shift 2 ;;
    --api) API="$2"; shift 2 ;;
    --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
    --work-dir) WORK_DIR="$2"; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

if [ -z "$FFMPEG_ARCHIVE" ] || [ -z "$X264_ARCHIVE" ] || [ -z "$NDK_DIR" ] || [ -z "$OUTPUT_DIR" ] || [ -z "$WORK_DIR" ]; then
  echo "用法：$0 --ffmpeg-archive <tar.xz> --x264-archive <tar.gz> --ndk-dir <ndk> --output-dir <out> --work-dir <work> [--api 31]" >&2
  exit 2
fi

PREBUILT_ROOT=""
for candidate in "$NDK_DIR"/toolchains/llvm/prebuilt/*; do
  if [ -d "$candidate/bin" ]; then
    PREBUILT_ROOT="$candidate"
    break
  fi
done
if [ -z "$PREBUILT_ROOT" ]; then
  echo "找不到 NDK 预编译工具链目录：$NDK_DIR" >&2
  exit 1
fi

TOOLCHAIN="$PREBUILT_ROOT/bin"
SYSROOT="$PREBUILT_ROOT/sysroot"
TARGET="aarch64-linux-android"
NDK_CC="$TOOLCHAIN/${TARGET}${API}-clang"
NDK_CXX="$TOOLCHAIN/${TARGET}${API}-clang++"
AR="$TOOLCHAIN/llvm-ar"
RANLIB="$TOOLCHAIN/llvm-ranlib"
STRIP="$TOOLCHAIN/llvm-strip"
NM="$TOOLCHAIN/llvm-nm"
READELF="$TOOLCHAIN/llvm-readelf"

JOBS="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/src/ffmpeg" "$WORK_DIR/src/x264" "$WORK_DIR/build-x264" "$WORK_DIR/build-ffmpeg" "$WORK_DIR/x264-prefix" "$WORK_DIR/stage" "$WORK_DIR/bin" "$OUTPUT_DIR"

tar -xf "$X264_ARCHIVE" -C "$WORK_DIR/src/x264" --strip-components=1
tar -xf "$FFMPEG_ARCHIVE" -C "$WORK_DIR/src/ffmpeg" --strip-components=1

X264_PREFIX="$WORK_DIR/x264-prefix"
BIN="$WORK_DIR/bin"

# 构造极简编译器与 pkg-config 包装脚本，避免把宿主机绝对路径写入 FFMPEG_CONFIGURATION 或调试前缀，
# 并在链接阶段直接传入字面量 -Wl,-rpath,$ORIGIN（绕过 Make/shell 二次展开）。
cat > "$BIN/nx-cc" <<EOF
#!/usr/bin/env bash
is_compile=0
for arg in "\$@"; do
  case "\$arg" in
    -c|-E|-S) is_compile=1; break ;;
  esac
done
if [ "\$is_compile" -eq 0 ]; then
  exec "$NDK_CC" --sysroot="$SYSROOT" -ffile-prefix-map="$WORK_DIR=/build/ff" -fdebug-prefix-map="$WORK_DIR=/build/ff" -I"$X264_PREFIX/include" '-Wl,-rpath,\$ORIGIN' "\$@"
else
  exec "$NDK_CC" --sysroot="$SYSROOT" -ffile-prefix-map="$WORK_DIR=/build/ff" -fdebug-prefix-map="$WORK_DIR=/build/ff" -I"$X264_PREFIX/include" "\$@"
fi
EOF
cat > "$BIN/nx-cxx" <<EOF
#!/usr/bin/env bash
exec "$NDK_CXX" --sysroot="$SYSROOT" -ffile-prefix-map="$WORK_DIR=/build/ff" -fdebug-prefix-map="$WORK_DIR=/build/ff" "\$@"
EOF
cat > "$BIN/nx-pkg-config" <<EOF
#!/usr/bin/env bash
out=""
has_x264=0
for arg in "\$@"; do
  case "\$arg" in
    --version) echo "0.29.2"; exit 0 ;;
    x264) has_x264=1 ;;
  esac
done
[ "\$has_x264" -eq 1 ] || exit 1
for arg in "\$@"; do
  case "\$arg" in
    --exists) ;;
    --modversion) echo "0.164"; exit 0 ;;
    --cflags) out="\$out -I$X264_PREFIX/include" ;;
    --libs) out="\$out -L$X264_PREFIX/lib -lx264 -lm" ;;
  esac
done
if [ -n "\$out" ]; then
  echo "\$out"
fi
exit 0
EOF
chmod +x "$BIN/nx-cc" "$BIN/nx-cxx" "$BIN/nx-pkg-config"

export PATH="$BIN:$TOOLCHAIN:$PATH"

# 1. 编译 8-bit 静态 libx264
(
  cd "$WORK_DIR/build-x264"
  CC="$NDK_CC" AS="$NDK_CC" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
    "$WORK_DIR/src/x264/configure" \
    --prefix="$X264_PREFIX" \
    --host="$TARGET" \
    --sysroot="$SYSROOT" \
    --bit-depth=8 \
    --enable-static \
    --enable-pic \
    --disable-cli \
    --disable-opencl \
    --disable-avs \
    --disable-swscale \
    --disable-lavf \
    --disable-ffms \
    --disable-gpac \
    --disable-lsmash \
    --extra-cflags="-Os -fPIC -ffunction-sections -fdata-sections -ffile-prefix-map=$WORK_DIR=/build/ff -fdebug-prefix-map=$WORK_DIR=/build/ff" \
    --extra-ldflags="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" >/dev/null
  make -j"$JOBS" >/dev/null
  make install-lib-static >/dev/null
)

# 2. 编译共享库版 FFmpeg + ffprobe（相对路径执行 configure，杜绝宿主路径进入二进制）
(
  cd "$WORK_DIR/build-ffmpeg"
  ../src/ffmpeg/configure \
    --prefix=/usr \
    --target-os=android \
    --arch=aarch64 \
    --cpu=armv8-a \
    --enable-cross-compile \
    --cc=nx-cc \
    --cxx=nx-cxx \
    --ar=llvm-ar \
    --ranlib=llvm-ranlib \
    --strip=llvm-strip \
    --nm=llvm-nm \
    --pkg-config=nx-pkg-config \
    --enable-shared \
    --disable-static \
    --build-suffix=_nxff \
    --enable-gpl \
    --enable-libx264 \
    --enable-small \
    --optflags="-Os" \
    --extra-cflags="-Os -fPIC -ffunction-sections -fdata-sections" \
    --extra-ldflags="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--gc-sections" \
    --disable-everything \
    --disable-doc \
    --disable-debug \
    --disable-avdevice \
    --disable-network \
    --disable-autodetect \
    --enable-zlib \
    --enable-ffmpeg \
    --enable-ffprobe \
    --enable-protocol=file,pipe,concat \
    --enable-demuxer=mov,matroska,wav,mp3,flac,ogg,image2,concat,rawvideo \
    --enable-muxer=mp4,mov,matroska,webm,wav,flac,image2,null \
    --enable-parser=h264,hevc,vp8,vp9,aac,opus,mpegaudio,flac,mjpeg,png \
    --enable-decoder=h264,hevc,vp8,vp9,aac,opus,mp3,flac,vorbis,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,png,mjpeg,webp,rawvideo \
    --enable-encoder=libx264,aac,pcm_s16le,flac,png,rawvideo \
    --enable-bsf=h264_mp4toannexb,hevc_mp4toannexb,aac_adtstoasc \
    --enable-filter=scale,fps,format,aformat,crop,pad,trim,atrim,setpts,asetpts,concat,aresample,null,anull,copy,acopy,setsar,setdar,transpose,hflip,vflip >/dev/null

  # 去除版本号 SONAME 后缀，使 Android jniLibs 直接打包无版本号的 lib*_nxff.so。
  python3 - ffbuild/config.mak <<'PY'
import sys
from pathlib import Path
p = Path(sys.argv[1])
lines = []
for line in p.read_text(encoding="utf-8").splitlines():
    if line.startswith("SLIBNAME_WITH_MAJOR="):
        lines.append("SLIBNAME_WITH_MAJOR=$(SLIBNAME)")
    elif line.startswith("SLIBNAME_WITH_VERSION="):
        lines.append("SLIBNAME_WITH_VERSION=$(SLIBNAME)")
    elif line.startswith("SLIB_INSTALL_LINKS="):
        lines.append("SLIB_INSTALL_LINKS=")
    elif line.startswith("SLIB_INSTALL_NAME="):
        lines.append("SLIB_INSTALL_NAME=$(SLIBNAME)")
    else:
        lines.append(line)
p.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY

  make -j"$JOBS" >/dev/null
  make install DESTDIR="$WORK_DIR/stage" >/dev/null
)

cp "$WORK_DIR/stage/usr/bin/ffmpeg" "$OUTPUT_DIR/libnxffmpeg.so"
cp "$WORK_DIR/stage/usr/bin/ffprobe" "$OUTPUT_DIR/libnxffprobe.so"
for lib in libavcodec_nxff.so libavfilter_nxff.so libavformat_nxff.so libavutil_nxff.so libswresample_nxff.so libswscale_nxff.so; do
  cp -L "$WORK_DIR/stage/usr/lib/$lib" "$OUTPUT_DIR/$lib"
done

"$STRIP" --strip-unneeded "$OUTPUT_DIR"/*.so

# 自检所有 8 个 ELF 的 16KB LOAD 页对齐（跨 macOS/Linux 可移植检查）
python3 - "$READELF" "$OUTPUT_DIR" <<'PY'
import subprocess, sys
from pathlib import Path

readelf, out_dir = sys.argv[1], Path(sys.argv[2])
for so in sorted(out_dir.glob("*.so")):
    out = subprocess.check_output([readelf, "-lW", str(so)], text=True)
    for line in out.splitlines():
        parts = line.split()
        if parts and parts[0] == "LOAD":
            align = int(parts[-1], 0)
            if align < 16384:
                sys.exit(f"16KB 页对齐校验失败：{so.name} (LOAD align={parts[-1]})")
PY
