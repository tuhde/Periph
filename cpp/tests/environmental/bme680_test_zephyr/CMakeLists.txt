cmake_minimum_required(VERSION 3.20)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(bme680_test_zephyr)

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../..)

target_sources(app PRIVATE
    src/main.cpp
    ${CPP_DIR}/src/chips/environmental/BME680.cpp
)

target_include_directories(app PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/environmental
)
