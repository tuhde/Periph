cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(ssd1331_complete_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_executable(ssd1331_complete_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/display/SSD1331.cpp
)

target_include_directories(ssd1331_complete_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/display
)

target_link_libraries(ssd1331_complete_picosdk PRIVATE
    pico_stdlib
    hardware_spi
    hardware_gpio)

pico_enable_stdio_usb(ssd1331_complete_picosdk 1)
pico_enable_stdio_uart(ssd1331_complete_picosdk 0)

pico_add_extra_outputs(ssd1331_complete_picosdk)
