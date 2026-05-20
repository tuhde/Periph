cmake_minimum_required(VERSION 3.20)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(pcf8574_complete_zephyr)

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../..)

target_sources(app PRIVATE
    src/main.cpp
    ${CPP_DIR}/src/chips/io_expander/PCF8574.cpp
)

target_include_directories(app PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/io_expander
)
