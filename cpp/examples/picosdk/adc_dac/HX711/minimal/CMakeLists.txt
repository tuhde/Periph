cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(hx711_minimal_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../)

add_executable(hx711_minimal_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/adc_dac/HX711.cpp
)

target_include_directories(hx711_minimal_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/adc_dac
)

target_link_libraries(hx711_minimal_picosdk PRIVATE
    pico_stdlib
    hardware_gpio
    pico_time)

pico_enable_stdio_usb(hx711_minimal_picosdk 1)
pico_enable_stdio_uart(hx711_minimal_picosdk 0)

pico_add_extra_outputs(hx711_minimal_picosdk)
