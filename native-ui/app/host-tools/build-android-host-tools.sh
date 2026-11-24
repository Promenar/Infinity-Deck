#!/usr/bin/env bash
# 在 macOS 或 Linux 上用 Android NDK API 31 交叉编译侧载宿主工具。
set -euo pipefail

if [ "$#" -ne 6 ]; then
  echo "用法：$0 <源码缓存> <NDK> <API> <新工作目录> <输出目录> <发行验证脚本>" >&2
  exit 2
fi

SOURCES=$1
NDK_DIR=$2
API=$3
WORK_DIR=$4
OUTPUT_DIR=$5
RELEASE_VERIFIER=$6
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
LOCKFILE="$SCRIPT_DIR/host-tools-android.properties"
TARGET=aarch64-linux-android
TOOLCHAIN=
for prebuilt in "$NDK_DIR"/toolchains/llvm/prebuilt/*; do
  if [ -x "$prebuilt/bin/${TARGET}${API}-clang" ]; then
    TOOLCHAIN="$prebuilt/bin"
    break
  fi
done
if [ -z "$TOOLCHAIN" ]; then
  echo "找不到 Android NDK API $API 的 AArch64 编译器" >&2
  exit 1
fi

MAKE=$(command -v gmake || command -v make)
JOBS=$(nproc 2>/dev/null || sysctl -n hw.ncpu)
JOBS=$((JOBS < 8 ? JOBS : 8))
umask 022
export LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH=1 ZERO_AR_DATE=1
export CONFIG_SITE=/dev/null PKG_CONFIG_LIBDIR=/dev/null PKG_CONFIG_PATH=
export CC="$TOOLCHAIN/${TARGET}${API}-clang" CXX="$TOOLCHAIN/${TARGET}${API}-clang++"
export AR="$TOOLCHAIN/llvm-ar" RANLIB="$TOOLCHAIN/llvm-ranlib" NM="$TOOLCHAIN/llvm-nm"
export STRIP="$TOOLCHAIN/llvm-strip" LD="$TOOLCHAIN/ld.lld"
export CARGO_HOME="${CARGO_HOME:-$HOME/.cargo}" RUSTUP_HOME="${RUSTUP_HOME:-$HOME/.rustup}"

# 仅在由调用者新建的工作目录中创建源码树，避免重试混用旧对象。
mkdir "$WORK_DIR/src" "$WORK_DIR/deps"
mkdir -p "$OUTPUT_DIR"
WORK_DIR=$(cd "$WORK_DIR" && pwd -P)
SRC="$WORK_DIR/src"
DEPS="$WORK_DIR/deps"
mkdir "$DEPS/include" "$DEPS/lib"

CFLAGS="-O2 -g0 -fPIC -ffunction-sections -fdata-sections"
for mapping in "$HOME=/build/host-user" "$WORK_DIR=/build/host-tools" "$SCRIPT_DIR=/build/host-tools/helpers"; do
  CFLAGS="$CFLAGS -ffile-prefix-map=$mapping -fdebug-prefix-map=$mapping"
done
PAGES="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
export CFLAGS CPPFLAGS="-I$DEPS/include" CXXFLAGS="$CFLAGS"
export LDFLAGS="$PAGES -Wl,--gc-sections -L$DEPS/lib"
unset LIBS SDKROOT CPATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH LIBRARY_PATH MAKEFLAGS

unpack() {
  local package=$1 archive expected actual
  archive=$(sed -n "s/^$package\.archive=//p" "$LOCKFILE")
  expected=$(sed -n "s/^$package\.sha256=//p" "$LOCKFILE")
  actual=$(shasum -a 256 "$SOURCES/$archive")
  if [ "${actual%% *}" != "$expected" ]; then
    echo "源码归档 SHA-256 不符：$archive" >&2
    exit 1
  fi
  mkdir "$SRC/$package"
  case "$archive" in
    *.zip) unzip -q -j "$SOURCES/$archive" -d "$SRC/$package" ;;
    *) tar -xf "$SOURCES/$archive" -C "$SRC/$package" --strip-components=1 ;;
  esac
}

configure() {
  local package=$1
  shift
  local guess
  guess=$(find "$SRC/$package" -name config.guess -print -quit)
  ./configure --host="$TARGET" --build="$(sh "$guess")" "$@"
}

install_tool() {
  cp "$1" "$OUTPUT_DIR/libnx$2.so"
  chmod 0755 "$OUTPUT_DIR/libnx$2.so"
}

for package in git curl zstd xz bzip2 libarchive jq sqlite rsync ripgrep fd; do
  unpack "$package"
done

# curl 仅提供 HTTP 传输。上游 HTTPS 由应用凭据代理终止。
(
  cd "$SRC/curl"
  configure curl --disable-shared --enable-static --without-ssl --without-zlib \
    --without-brotli --without-zstd --without-libpsl --without-libidn2 --without-apple-idn \
    --without-nghttp2 --without-libgsasl --disable-ares --disable-manual --disable-docs \
    --disable-ftp --disable-file --disable-ipfs --disable-ldap --disable-ldaps --disable-rtsp \
    --disable-dict --disable-telnet --disable-tftp --disable-pop3 --disable-imap --disable-smtp \
    --disable-gopher --disable-mqtt --disable-websockets
  "$MAKE" -j "$JOBS" -C lib libcurl.la
)

(
  cd "$SRC/git"
  # Bionic 没有 pthread cancellation，保留 Git 的线程与 fork 前后的信号屏蔽。
  patch -p1 < "$SCRIPT_DIR/git-bionic.patch"
  # Make 自身用宿主 /bin/sh，Git 运行时用 Android 的 shell。Git 的 HTTP(S) helper 共用一个 ELF。
  "$MAKE" -j "$JOBS" uname_S=Android uname_M=aarch64 prefix=/system \
    CC="$CC" AR="$AR" CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS" \
    'SHELL_PATH_CQ="/system/bin/sh"' \
    NO_PERL=YesPlease NO_PYTHON=YesPlease NO_TCLTK=YesPlease NO_GETTEXT=YesPlease \
    NO_EXPAT=YesPlease NO_ICONV=YesPlease NO_PCRE=YesPlease NO_OPENSSL=YesPlease NO_RUST=YesPlease \
    NO_LIBGEN_H=YesPlease HAVE_CLOCK_GETTIME=YesPlease \
    HAVE_DEV_TTY=YesPlease CSPRNG_METHOD=arc4random PTHREAD_LIBS=-pthread \
    CURL_CFLAGS="-I$SRC/curl/include" CURL_LIBCURL="$SRC/curl/lib/.libs/libcurl.a" \
    git git-remote-http
  install_tool git git
  install_tool git-remote-http git-remote-https
)

(
  cd "$SRC/zstd"
  "$MAKE" -C lib -j "$JOBS" CPPFLAGS_STATICLIB=-DZSTD_MULTITHREAD libzstd.a
  cp lib/libzstd.a "$DEPS/lib/"
  cp lib/zstd.h lib/zstd_errors.h "$DEPS/include/"
  "$MAKE" -C programs -j "$JOBS" HAVE_THREAD=1 HAVE_ZLIB=1 HAVE_LZMA=0 HAVE_LZ4=0 zstd-release
  install_tool programs/zstd zstd
)

(
  cd "$SRC/xz"
  configure xz --disable-shared --enable-static --disable-nls --disable-doc --enable-threads=posix
  "$MAKE" -j "$JOBS" -C src/liblzma V=0
  cp src/liblzma/.libs/liblzma.a "$DEPS/lib/"
  cp src/liblzma/api/lzma.h "$DEPS/include/"
  cp -R src/liblzma/api/lzma "$DEPS/include/"
)

(
  cd "$SRC/bzip2"
  "$MAKE" -j "$JOBS" CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="$CFLAGS -D_FILE_OFFSET_BITS=64" libbz2.a
  cp libbz2.a "$DEPS/lib/"
  cp bzlib.h "$DEPS/include/"
)

(
  cd "$SRC/libarchive"
  CPPFLAGS="$CPPFLAGS -I$SRC/libarchive/contrib/android/include" \
    configure libarchive --disable-shared --enable-static --disable-rpath \
    --enable-bsdtar --disable-bsdcat --disable-bsdcpio --disable-bsdunzip \
    --enable-posix-regex-lib=libc --without-iconv --without-lz4 --without-libb2 \
    --without-openssl --without-md --without-xml2 --without-expat
  for macro in HAVE_LIBZ HAVE_LIBBZ2 HAVE_LIBLZMA HAVE_LIBZSTD; do
    if ! grep -q "^#define $macro 1$" config.h; then
      echo "libarchive 缺少必需的压缩后端：$macro" >&2
      exit 1
    fi
  done
  "$MAKE" -j "$JOBS" V=0 bsdtar
  install_tool bsdtar bsdtar
)

(
  cd "$SRC/rsync"
  configure rsync --with-included-popt --with-included-zlib --disable-md2man \
    --disable-openssl --disable-xxhash --disable-lz4 --disable-iconv-open --disable-idn
  if ! grep -q '^#define SUPPORT_ZSTD 1$' config.h; then
    echo "rsync 缺少 zstd 支持" >&2
    exit 1
  fi
  "$MAKE" -j "$JOBS" rsync
  install_tool rsync rsync
)

(
  cd "$SRC/jq"
  jq_options=(--disable-shared --enable-static --disable-docs --with-oniguruma=builtin)
  configure jq "${jq_options[@]}"
  # jq 会将 configure 的 CC/CFLAGS 等宿主路径放进 --build-configuration。
  printf '#define JQ_CONFIG "--host=%s %s"\n' "$TARGET" "${jq_options[*]}" > src/config_opts.inc
  "$MAKE" -j "$JOBS" V=0 all
  install_tool jq jq
)

(
  cd "$SRC/sqlite"
  # SQLite 自 3.38 默认内建 JSON，保留显式选项以固定编译契约。
  "$CC" $CFLAGS $LDFLAGS -DSQLITE_ENABLE_FTS5 -DSQLITE_ENABLE_JSON1 \
    -DSQLITE_ENABLE_RTREE -DSQLITE_ENABLE_MATH_FUNCTIONS -DSQLITE_DQS=0 \
    sqlite3.c shell.c -lm -ldl -o sqlite3
  install_tool sqlite3 sqlite3
)

build_rust() (
  export CARGO_TARGET_DIR="$WORK_DIR/cargo-target"
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
  export CC_aarch64_linux_android="$CC" AR_aarch64_linux_android="$AR"
  export CARGO_PROFILE_RELEASE_LTO=true CARGO_PROFILE_RELEASE_PANIC=abort
  export CARGO_PROFILE_RELEASE_STRIP=symbols CARGO_PROFILE_RELEASE_DEBUG=0
  export CARGO_PROFILE_RELEASE_INCREMENTAL=false CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1
  local separator=$'\x1f' mapping flags
  flags="-Clink-arg=-Wl,-z,max-page-size=16384${separator}-Clink-arg=-Wl,-z,common-page-size=16384"
  for mapping in "$HOME=/build/host-user" "$WORK_DIR=/build/host-tools" \
    "$CARGO_HOME=/build/cargo" "$RUSTUP_HOME=/build/rustup"; do
    flags="$flags${separator}--remap-path-prefix=$mapping"
  done
  export CARGO_ENCODED_RUSTFLAGS="$flags"
  cargo build --release --locked --jobs "$JOBS" --target "$TARGET" \
    --manifest-path "$SRC/$1/Cargo.toml" --bin "$2"
  install_tool "$CARGO_TARGET_DIR/$TARGET/release/$2" "$2"
)

build_rust ripgrep rg
build_rust fd fd

"$CC" $CFLAGS $LDFLAGS -std=c11 -Wall -Wextra -Werror \
  "$SCRIPT_DIR/git-ssh-helper.c" "$SCRIPT_DIR/bridge-protocol.c" -o "$OUTPUT_DIR/libnxgitssh.so"
"$CC" $CFLAGS $LDFLAGS -std=c11 -Wall -Wextra -Werror \
  -ffreestanding -fno-builtin -fno-stack-protector -nostdlib -static -fPIE \
  -Wl,-e,_start "$SCRIPT_DIR/tool-shim.c" "$SCRIPT_DIR/bridge-protocol.c" -o "$OUTPUT_DIR/libnxtoolshim.so"

"$STRIP" --strip-unneeded "$OUTPUT_DIR"/libnx*.so
python3 "$SCRIPT_DIR/verify-host-tools.py" "$RELEASE_VERIFIER" "$TOOLCHAIN/llvm-readelf" "$OUTPUT_DIR" \
  "$WORK_DIR" "$SCRIPT_DIR" "$CARGO_HOME" "$RUSTUP_HOME" "$HOME" "$NDK_DIR"
