cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(shtc3_minimal_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_executable(shtc3_minimal_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/humidity/Shtc3.cpp
)

target_include_directories(shtc3_minimal_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/humidity
)

target_link_libraries(shtc3_minimal_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(shtc3_minimal_picosdk 1)
pico_enable_stdio_uart(shtc3_minimal_picosdk 0)

pico_add_extra_outputs(shtc3_minimal_picosdk)
