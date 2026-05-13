cmake_minimum_required(VERSION 3.22.1)
project(quickjs_engine)

# QuickJS source files from the submodule.
set(QUICKJS_DIR ${CMAKE_SOURCE_DIR}/quickjs_ng)

add_library(
    quickjs_engine
    SHARED
    quickjs_engine.c
    ${QUICKJS_DIR}/quickjs.c
    ${QUICKJS_DIR}/libregexp.c
    ${QUICKJS_DIR}/libunicode.c
    ${QUICKJS_DIR}/dtoa.c
)

target_include_directories(quickjs_engine PRIVATE ${QUICKJS_DIR})

target_compile_definitions(quickjs_engine PRIVATE
    QUICKJS_NG_BUILD
    _GNU_SOURCE
    CONFIG_VERSION=\"opentune\"
)

target_compile_options(quickjs_engine PRIVATE
    -O2
    -fvisibility=hidden
    -Wno-unused-parameter
)

find_library(log-lib log)
target_link_libraries(quickjs_engine ${log-lib})
