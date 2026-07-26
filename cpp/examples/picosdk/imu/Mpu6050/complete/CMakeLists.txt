cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(mpu6050_complete_picosdk C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../)

add_executable(mpu6050_complete_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/imu/Mpu6050.cpp
)

target_include_directories(mpu6050_complete_picosdk PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/imu
)

target_link_libraries(mpu6050_complete_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(mpu6050_complete_picosdk 1)
pico_enable_stdio_uart(mpu6050_complete_picosdk 0)

pico_add_extra_outputs(mpu6050_complete_picosdk)
