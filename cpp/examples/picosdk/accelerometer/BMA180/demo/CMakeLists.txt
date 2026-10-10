cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(bma180_demo_picosdk CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_executable(bma180_demo_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/accelerometer/BMA180.cpp
)
target_include_directories(bma180_demo_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/accelerometer
)
target_link_libraries(bma180_demo_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(bma180_demo_picosdk 1)
pico_enable_stdio_uart(bma180_demo_picosdk 0)

pico_add_extra_outputs(bma180_demo_picosdk)