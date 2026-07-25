cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(neo6_demo_picosdk CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../)

add_executable(neo6_demo_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/gnss/NEO6.cpp
)

target_include_directories(neo6_demo_picosdk PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/gnss
)

target_link_libraries(neo6_demo_picosdk PRIVATE
    pico_stdlib
    hardware_uart
    hardware_gpio)

pico_enable_stdio_usb(neo6_demo_picosdk 1)
pico_enable_stdio_uart(neo6_demo_picosdk 0)

pico_add_extra_outputs(neo6_demo_picosdk)
