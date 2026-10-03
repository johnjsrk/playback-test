cmake_minimum_required(VERSION 3.22.1)
project(aaudioplayer)

add_library(aaudioplayer SHARED aaudio_player.cpp)

# 16KB 페이지 크기 기기 호환 (NDK r25는 기본이 4KB 정렬이라 직접 지정)
target_link_options(aaudioplayer PRIVATE -Wl,-z,max-page-size=16384)

find_library(log-lib log)
target_link_libraries(aaudioplayer aaudio ${log-lib})