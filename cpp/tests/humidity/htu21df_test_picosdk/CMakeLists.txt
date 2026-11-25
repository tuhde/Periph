cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(htu21df_test_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../..)

add_executable(htu21df_test_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/humidity/HTU21DF.cpp
)

target_include_directories(htu21df_test_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/humidity
)

target_link_libraries(htu21df_test_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(htu21df_test_picosdk 1)
pico_enable_stdio_uart(htu21df_test_picosdk 0)

pico_add_extra_outputs(htu21df_test_picosdk)
