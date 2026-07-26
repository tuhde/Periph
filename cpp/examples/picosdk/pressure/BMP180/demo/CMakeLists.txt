cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(bmp180_demo_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../)

add_executable(bmp180_demo_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/pressure/BMP180.cpp
)

target_include_directories(bmp180_demo_picosdk PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/pressure
)

target_link_libraries(bmp180_demo_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(bmp180_demo_picosdk 1)
pico_enable_stdio_uart(bmp180_demo_picosdk 0)

pico_add_extra_outputs(bmp180_demo_picosdk)
