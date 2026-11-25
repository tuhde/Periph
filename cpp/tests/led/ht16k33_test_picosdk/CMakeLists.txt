cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(ht16k33_test_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../..)

add_executable(ht16k33_test_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/led/HT16K33.cpp
)

target_include_directories(ht16k33_test_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/led
)

target_link_libraries(ht16k33_test_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(ht16k33_test_picosdk 1)
pico_enable_stdio_uart(ht16k33_test_picosdk 0)

pico_add_extra_outputs(ht16k33_test_picosdk)
