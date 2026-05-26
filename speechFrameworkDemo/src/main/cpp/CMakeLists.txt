cmake_minimum_required(VERSION 3.18.1)
project(app CXX)

set(CMAKE_CXX_STANDARD 11)

# Build Opus as static lib and disable programs/tests
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include(FetchContent)
# URL tarball thay vì GIT — tránh _deps/opus-src/.git bị khóa trên Windows (AccessDeniedException)
FetchContent_Declare(opus
  URL https://github.com/xiph/opus/archive/refs/tags/v1.4.tar.gz
  URL_HASH SHA256=659e6b223e42a51b0a898632b9a5f406ccd5c2e00aa526ddd1264789774b94e5
)
FetchContent_MakeAvailable(opus)

# JNI library "app" (System.loadLibrary("app"))
add_library(app SHARED
  opus_encoder_jni.cpp
  opus_decoder_jni.cpp
)

target_include_directories(app PRIVATE
  ${opus_SOURCE_DIR}/include
  ${opus_BINARY_DIR}
)

target_link_libraries(app
  opus
  log
)
