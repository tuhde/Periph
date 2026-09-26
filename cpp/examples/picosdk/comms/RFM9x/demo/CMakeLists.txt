cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(rfm9x_demo_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_executable(rfm9x_demo_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/comms/RFM9x.cpp
)

target_include_directories(rfm9x_demo_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/comms
)

target_link_libraries(rfm9x_demo_picosdk PRIVATE
    pico_stdlib
    hardware_spi)

pico_enable_stdio_usb(rfm9x_demo_picosdk 1)
pico_enable_stdio_uart(rfm9x_demo_picosdk 0)

pico_add_extra_outputs(rfm9x_demo_picosdk)
