cmake_minimum_required(VERSION 3.20)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(smbus_test_zephyr)

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../..)

target_sources(app PRIVATE
    src/main.cpp
)

target_include_directories(app PRIVATE
    ${CPP_DIR}/src/transport
)
