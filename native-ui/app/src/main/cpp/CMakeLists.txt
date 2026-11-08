cmake_minimum_required(VERSION 3.22.1)
project("infinity_llama")

option(INFINITY_ENABLE_LOCAL_INFERENCE "Build the local llama.cpp inference engine" OFF)

# 本工程所有共享库（含 FetchContent 拉取的 llama/ggml）显式按 16KB 最大页大小链接，兼容 16KB 页设备；
# APK 级检查见 scripts/ci/check-native-page-alignment.py。
string(APPEND CMAKE_SHARED_LINKER_FLAGS " -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

# 工作区持久文件身份只依赖NDK/libc；所有变体均提供，不触发推理引擎下载或初始化。
add_library(infinity_workspace_fs SHARED workspace-file-identity.cpp)
set_target_properties(infinity_workspace_fs PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)

# 终端 PTY 桥接：重新编译 terminal-emulator 的 JNI 源码，替换其 AAR 内按 4KB 对齐的 libtermux.so（来源见 termux/README.md）。
add_library(termux SHARED termux/termux.c)

if(NOT INFINITY_ENABLE_LOCAL_INFERENCE)
    # 推理关闭时仍构建工作区文件身份与终端 PTY 桥接。
    set(_infinity_disabled_variant_inputs
        "${CMAKE_EXPORT_COMPILE_COMMANDS}"
        "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
        "${CMAKE_RUNTIME_OUTPUT_DIRECTORY}"
    )
    unset(_infinity_disabled_variant_inputs)
    message(STATUS "Infinity Deck local inference is disabled for this variant")
    return()
endif()

# ──────────────────────────────────────────────────────────────
# Option: Use pre-built llama.cpp .so files instead of building
# from source. Set -DINFINITY_LLAMA_PREBUILT=ON to enable.
#
# When PREBUILT=ON, place .so files at:
#   app/src/main/jniLibs/arm64-v8a/libllama.so
#   app/src/main/jniLibs/armeabi-v7a/libllama.so
# And place llama.h header in: app/src/main/cpp/llama.h
# ──────────────────────────────────────────────────────────────
option(INFINITY_LLAMA_PREBUILT "Use pre-built llama.cpp .so files" OFF)

if(NOT INFINITY_LLAMA_PREBUILT)
    # ── Build llama.cpp from source via FetchContent ──
    include(FetchContent)

    # Configure llama.cpp options BEFORE fetching
    set(GGML_VULKAN            OFF CACHE BOOL "Vulkan needs host shader cross-compilation; enable only with proper host build" FORCE)
    set(GGML_CUDA              OFF CACHE BOOL "Disable CUDA (not on Android)" FORCE)
    set(GGML_METAL             OFF CACHE BOOL "Disable Metal (not on Android)" FORCE)
    set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL             OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
        GIT_TAG        master
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(llama_cpp)
endif()

# ── JNI bridge library ──
add_library(infinity_llama SHARED native-lib.cpp)

# Android log library
find_library(log-lib log)

if(NOT INFINITY_LLAMA_PREBUILT)
    target_link_libraries(infinity_llama
        llama
        ${log-lib}
    )
    target_include_directories(infinity_llama PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${llama_cpp_SOURCE_DIR}/include
    )
else()
    target_link_libraries(infinity_llama ${log-lib})
    target_include_directories(infinity_llama PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
    )
endif()

# Strip debug symbols for release builds
set_target_properties(infinity_llama PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)
