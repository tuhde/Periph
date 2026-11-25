cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(drv2605_demo_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_executable(drv2605_demo_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/motor/DRV2605.cpp
)

target_include_directories(drv2605_demo_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/motor
)

target_link_libraries(drv2605_demo_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(drv2605_demo_picosdk 1)
pico_enable_stdio_uart(drv2605_demo_picosdk 0)

pico_add_extra_outputs(drv2605_demo_picosdk)
