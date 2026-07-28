cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(dht11_test_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../..)

add_executable(dht11_test_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/humidity/DHT11.cpp
)

target_include_directories(dht11_test_picosdk PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/humidity
)

target_link_libraries(dht11_test_picosdk PRIVATE
    pico_stdlib  hardware_gpio)

pico_enable_stdio_usb(dht11_test_picosdk 1)
pico_enable_stdio_uart(dht11_test_picosdk 0)

pico_add_extra_outputs(dht11_test_picosdk)
