cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(sh1106_demo_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_executable(sh1106_demo_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/display/SH1106.cpp
)

target_include_directories(sh1106_demo_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/display
)

target_link_libraries(sh1106_demo_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(sh1106_demo_picosdk 1)
pico_enable_stdio_uart(sh1106_demo_picosdk 0)

pico_add_extra_outputs(sh1106_demo_picosdk)
