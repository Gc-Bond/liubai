cmake_minimum_required(VERSION 3.22.1)
project(retrobridge C)
# bridge.c:游戏核心 JNI 桥。
add_library(retrobridge SHARED bridge.c)
# dlopen 核心用 dl；把像素推上屏用 android(ANativeWindow)；日志用 log
target_link_libraries(retrobridge android log dl)
