cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(il0373_demo_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_executable(il0373_demo_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/display/IL0373.cpp
)

target_include_directories(il0373_demo_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/display
)

target_link_libraries(il0373_demo_picosdk PRIVATE
    pico_stdlib
    hardware_spi
    hardware_gpio)

pico_enable_stdio_usb(il0373_demo_picosdk 1)
pico_enable_stdio_uart(il0373_demo_picosdk 0)

pico_add_extra_outputs(il0373_demo_picosdk)
