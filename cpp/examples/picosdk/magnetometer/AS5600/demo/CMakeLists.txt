cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(as5600_demo_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../)

add_executable(as5600_demo_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/magnetometer/AS5600.cpp
)

target_include_directories(as5600_demo_picosdk PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/magnetometer
)

target_link_libraries(as5600_demo_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(as5600_demo_picosdk 1)
pico_enable_stdio_uart(as5600_demo_picosdk 0)

pico_add_extra_outputs(as5600_demo_picosdk)
