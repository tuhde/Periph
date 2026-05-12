cmake_minimum_required(VERSION 3.20)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(mcp4725_complete_zephyr)

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../..)

target_sources(app PRIVATE
    src/main.cpp
    ${CPP_DIR}/src/chips/adc_dac/MCP4725.cpp
)

target_include_directories(app PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/adc_dac
)