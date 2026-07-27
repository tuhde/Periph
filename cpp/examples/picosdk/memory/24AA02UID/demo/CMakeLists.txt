cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(24aa02uid_demo_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../)

add_executable(24aa02uid_demo_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/memory/24AA02UID.cpp
)

target_include_directories(24aa02uid_demo_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/memory
)

target_link_libraries(24aa02uid_demo_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(24aa02uid_demo_picosdk 1)
pico_enable_stdio_uart(24aa02uid_demo_picosdk 0)

pico_add_extra_outputs(24aa02uid_demo_picosdk)
