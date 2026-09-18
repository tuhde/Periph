cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(l3gd20h_demo_picosdk CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../)

add_executable(l3gd20h_demo_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/gyroscope/L3gd20h.cpp
)
target_include_directories(l3gd20h_demo_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/gyroscope
)
target_link_libraries(l3gd20h_demo_picosdk PRIVATE
    pico_stdlib
    hardware_i2c
)
pico_enable_stdio_usb(l3gd20h_demo_picosdk 1)
pico_enable_stdio_uart(l3gd20h_demo_picosdk 0)
pico_add_extra_outputs(l3gd20h_demo_picosdk)