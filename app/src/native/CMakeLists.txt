cmake_minimum_required(VERSION 3.22.1)
project(rikka_native CXX)
set(CMAKE_CXX_STANDARD 17)

option(RIKKA_VV "Build VOICEVOX C API bridge" OFF)
option(RIKKA_PIPER "Build piper-plus bridge" OFF)

# VOICEVOX：只依赖 NDK，运行时 dlopen libvoicevox_core.so，不需要头文件也不用链接
if(RIKKA_VV)
    add_library(rikka_vv SHARED ${CMAKE_CURRENT_SOURCE_DIR}/../voicevox/cpp/vv_jni.cpp)
    target_link_libraries(rikka_vv log dl)
endif()

if(RIKKA_PIPER)
    set(PIPER_PLUS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/piper-plus-android)
    add_library(piper_plus SHARED IMPORTED)
    set_target_properties(piper_plus PROPERTIES IMPORTED_LOCATION ${PIPER_PLUS_DIR}/lib/${ANDROID_ABI}/libpiper_plus.so)
    add_library(rikka_piper_jni SHARED ${CMAKE_CURRENT_SOURCE_DIR}/../piperplus/cpp/piper_plus_jni.cpp)
    target_include_directories(rikka_piper_jni PRIVATE ${PIPER_PLUS_DIR}/include)
    target_link_libraries(rikka_piper_jni piper_plus log)
endif()
