cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(ad7706_complete_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_executable(ad7706_complete_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/adc_dac/AD7706.cpp
)

target_include_directories(ad7706_complete_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/adc_dac
)

target_link_libraries(ad7706_complete_picosdk PRIVATE
    pico_stdlib
    hardware_spi
    hardware_gpio)

pico_enable_stdio_usb(ad7706_complete_picosdk 1)
pico_enable_stdio_uart(ad7706_complete_picosdk 0)

pico_add_extra_outputs(ad7706_complete_picosdk)
