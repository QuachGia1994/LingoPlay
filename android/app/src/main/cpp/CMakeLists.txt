cmake_minimum_required(VERSION 3.22.1)
project(lingoplay_separation LANGUAGES CXX)

add_library(lingoplay-separation SHARED source_separation_jni.cpp)
target_compile_features(lingoplay-separation PRIVATE cxx_std_17)
target_compile_options(lingoplay-separation PRIVATE -Wall -Wextra -Werror)
target_link_libraries(lingoplay-separation PRIVATE dl log)
