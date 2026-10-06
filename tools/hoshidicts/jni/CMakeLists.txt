# libhoshidicts_jni.so for kumapie: hoshidicts (HOSHIDICTS_SRC) + jni/hoshidicts_jni.cpp. Built by ../build.sh.
cmake_minimum_required(VERSION 3.22)
project(hoshidicts_jni CXX C)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(${HOSHIDICTS_SRC} hoshidicts)
add_library(hoshidicts_jni SHARED hoshidicts_jni.cpp)
target_link_libraries(hoshidicts_jni PRIVATE hoshidicts)
target_link_options(hoshidicts_jni PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
