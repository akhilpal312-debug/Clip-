cmake_minimum_required(VERSION 3.22.1)
project(aiclipai C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2")

option(AICLIP_WITH_LLM "Build llama.cpp runtime for local caption generation" OFF)

include(FetchContent)

# ---------------------------------------------------------------------------
# whisper.cpp (official upstream, pinned tag)
# ---------------------------------------------------------------------------
set(WHISPER_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER      OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP               OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE            OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
        whisper_cpp
        GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
        GIT_TAG        v1.7.4
        GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(aiclipai SHARED
        whisper_jni.cpp
)

if (AICLIP_WITH_LLM)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_CUDA OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    set(GGML_METAL OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(
            llama_cpp
            GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
            GIT_TAG        b3982
            GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(llama_cpp)
    target_sources(aiclipai PRIVATE llm_jni.cpp)
    target_compile_definitions(aiclipai PRIVATE AICLIP_WITH_LLM=1)
    target_link_libraries(aiclipai llama ggml)
endif ()

target_link_libraries(aiclipai whisper android log)
target_compile_options(aiclipai PRIVATE -fexceptions -frtti)
