cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(tmc2208_complete_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_executable(tmc2208_complete_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/motor/TMC2208.cpp
)

target_include_directories(tmc2208_complete_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/motor
)

target_link_libraries(tmc2208_complete_picosdk PRIVATE
    pico_stdlib
    hardware_uart
    hardware_gpio)

pico_enable_stdio_usb(tmc2208_complete_picosdk 1)
pico_enable_stdio_uart(tmc2208_complete_picosdk 0)

pico_add_extra_outputs(tmc2208_complete_picosdk)
