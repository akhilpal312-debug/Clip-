cmake_minimum_required(VERSION 3.22.1)

# Stub native module used when the project is built with -PnativeAi=false.
# It keeps the JNI symbol table linkable without compiling whisper.cpp / llama.cpp.
project(aiclipai LANGUAGES CXX)
add_library(aiclipai SHARED stub.cpp)
target_compile_features(aiclipai PRIVATE cxx_std_17)
