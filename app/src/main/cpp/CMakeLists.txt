cmake_minimum_required(VERSION 3.22.1)

project(mydnd_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../llama.cpp")

message(STATUS "LLAMA_CPP_DIR = ${LLAMA_CPP_DIR}")

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)

add_subdirectory(
        ${LLAMA_CPP_DIR}
        ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp
)

add_library(
        mydnd_native
        SHARED
        native_bridge.cpp
)

target_include_directories(
        mydnd_native
        PRIVATE
        ${LLAMA_CPP_DIR}/include
        ${LLAMA_CPP_DIR}/ggml/include
        ${LLAMA_CPP_DIR}/common
)

find_library(
        log-lib
        log
)

target_link_libraries(
        mydnd_native
        llama
        ${log-lib}
)