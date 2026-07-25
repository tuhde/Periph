cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(pcf8591_complete_picosdk CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../)

add_executable(pcf8591_complete_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/adc_dac/PCF8591.cpp
)

target_include_directories(pcf8591_complete_picosdk PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/adc_dac
)

target_link_libraries(pcf8591_complete_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(pcf8591_complete_picosdk 1)
pico_enable_stdio_uart(pcf8591_complete_picosdk 0)

pico_add_extra_outputs(pcf8591_complete_picosdk)
