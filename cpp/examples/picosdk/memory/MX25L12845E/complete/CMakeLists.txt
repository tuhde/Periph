cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(mx25l12845e_complete_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_executable(mx25l12845e_complete_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/memory/MX25L12845E.cpp
)

target_include_directories(mx25l12845e_complete_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/memory
)

target_link_libraries(mx25l12845e_complete_picosdk PRIVATE
    pico_stdlib
    hardware_spi)

pico_enable_stdio_usb(mx25l12845e_complete_picosdk 1)
pico_enable_stdio_uart(mx25l12845e_complete_picosdk 0)

pico_add_extra_outputs(mx25l12845e_complete_picosdk)