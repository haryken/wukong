# Distributed under the OSI-approved BSD 3-Clause License.  See accompanying
# file Copyright.txt or https://cmake.org/licensing for details.

cmake_minimum_required(VERSION 3.18.1-g262b901-dirty)

# We name the project and the target for the ExternalProject_Add() call
# to something that will highlight to the user what we are working on if
# something goes wrong and an error message is produced.

project(opus-populate NONE)

include(ExternalProject)
ExternalProject_Add(opus-populate
                     "UPDATE_DISCONNECTED" "False" "URL" "https://github.com/xiph/opus/archive/refs/tags/v1.4.tar.gz" "URL_HASH" "SHA256=659e6b223e42a51b0a898632b9a5f406ccd5c2e00aa526ddd1264789774b94e5"
                    SOURCE_DIR          "C:/Users/LEGION/Desktop/code/ubt_speech_demo-develop3/ubt_speech_demo-develop2/speechFrameworkDemo/.cxx/cmake/debug/arm64-v8a/_deps/opus-src"
                    BINARY_DIR          "C:/Users/LEGION/Desktop/code/ubt_speech_demo-develop3/ubt_speech_demo-develop2/speechFrameworkDemo/.cxx/cmake/debug/arm64-v8a/_deps/opus-build"
                    CONFIGURE_COMMAND   ""
                    BUILD_COMMAND       ""
                    INSTALL_COMMAND     ""
                    TEST_COMMAND        ""
                    USES_TERMINAL_DOWNLOAD  YES
                    USES_TERMINAL_UPDATE    YES
)


