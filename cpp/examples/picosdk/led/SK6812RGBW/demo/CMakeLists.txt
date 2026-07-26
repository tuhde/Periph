cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(sk6812rgbw_demo_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../)

add_executable(sk6812rgbw_demo_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/led/SK6812RGBW.cpp
)

target_include_directories(sk6812rgbw_demo_picosdk PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/led
)

target_link_libraries(sk6812rgbw_demo_picosdk PRIVATE
    pico_stdlib
    hardware_spi)

pico_enable_stdio_usb(sk6812rgbw_demo_picosdk 1)
pico_enable_stdio_uart(sk6812rgbw_demo_picosdk 0)

pico_add_extra_outputs(sk6812rgbw_demo_picosdk)
