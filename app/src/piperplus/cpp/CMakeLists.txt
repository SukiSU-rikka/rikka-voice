cmake_minimum_required(VERSION 3.22.1)
project(rikka_piper_jni CXX)

# piper-plus 的 Android 预编译库与头文件（见 构建说明.md 第 4 步）
set(PIPER_PLUS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/piper-plus-android)

add_library(piper_plus SHARED IMPORTED)
set_target_properties(piper_plus PROPERTIES IMPORTED_LOCATION
    ${PIPER_PLUS_DIR}/lib/${ANDROID_ABI}/libpiper_plus.so)

add_library(rikka_piper_jni SHARED piper_plus_jni.cpp)
target_include_directories(rikka_piper_jni PRIVATE ${PIPER_PLUS_DIR}/include)
target_link_libraries(rikka_piper_jni piper_plus log)
