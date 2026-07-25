cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(apds9960_test_picosdk CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../..)

add_executable(apds9960_test_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/light/APDS9960.cpp
)

target_include_directories(apds9960_test_picosdk PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/light
)

target_link_libraries(apds9960_test_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(apds9960_test_picosdk 1)
pico_enable_stdio_uart(apds9960_test_picosdk 0)

pico_add_extra_outputs(apds9960_test_picosdk)
