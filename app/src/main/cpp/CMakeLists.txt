cmake_minimum_required(VERSION 3.22)
project(adb_spake2 LANGUAGES C CXX)

include(FetchContent)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Pin BoringSSL: its internal ABI is not stable between revisions.
FetchContent_Declare(
    boringssl
    GIT_REPOSITORY https://github.com/google/boringssl.git
    GIT_TAG 5fa9e920fe867353a5a0a6eee4005e20aa3df85d
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(boringssl)

add_library(adb_spake2 SHARED spake2_jni.cpp)
target_include_directories(adb_spake2 PRIVATE "${boringssl_SOURCE_DIR}/include")
target_compile_features(adb_spake2 PRIVATE cxx_std_17)
target_link_libraries(adb_spake2 PRIVATE crypto log)
