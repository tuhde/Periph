cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(sht31_demo_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_executable(sht31_demo_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/humidity/SHT31.cpp
)

target_include_directories(sht31_demo_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/humidity
)

target_link_libraries(sht31_demo_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(sht31_demo_picosdk 1)
pico_enable_stdio_uart(sht31_demo_picosdk 0)

pico_add_extra_outputs(sht31_demo_picosdk)
