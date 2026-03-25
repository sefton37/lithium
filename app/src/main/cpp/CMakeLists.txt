# CMakeLists.txt for Lithium's llama.cpp JNI bridge.
#
# Build requirements:
#   - Android NDK r26+ (install via Android Studio SDK Manager or sdkmanager)
#   - llama.cpp source vendored at vendor/llama.cpp/
#   - Build flag: ./gradlew :app:assembleDebug -PenableLlama
#
# To vendor llama.cpp:
#   cd app/src/main/cpp
#   git clone --depth 1 --branch b4773 https://github.com/ggerganov/llama.cpp.git vendor/llama.cpp
#
# The resulting .so is ~3-5MB and ships only for arm64-v8a (Pixel trial target).

cmake_minimum_required(VERSION 3.22.1)
project(llama_jni VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# -- llama.cpp source --
# Expects the llama.cpp repo at vendor/llama.cpp/
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/llama.cpp)

# Disable features we don't need to speed up the build
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)

# Build llama.cpp as a static library
add_subdirectory(${LLAMA_DIR} llama_build)

# -- JNI bridge --
add_library(llama_jni SHARED llama_jni.cpp)

target_include_directories(llama_jni PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(llama_jni
    llama
    ggml
    android
    log
)
