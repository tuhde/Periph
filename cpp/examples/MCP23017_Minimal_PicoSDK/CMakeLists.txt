cmake_minimum_required(VERSION 3.13)
include($ENV{PICO_SDK_PATH}/pico_sdk_init.cmake)

project(mcp23017_minimal_picosdk CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../..)

add_executable(mcp23017_minimal_picosdk
    src/main.cpp
    ${CPP_DIR}/src/chips/io_expander/MCP23017.cpp
)

target_include_directories(mcp23017_minimal_picosdk PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/io_expander
)

target_link_libraries(mcp23017_minimal_picosdk PRIVATE
    pico_stdlib
    hardware_i2c)

pico_enable_stdio_usb(mcp23017_minimal_picosdk 1)
pico_enable_stdio_uart(mcp23017_minimal_picosdk 0)

pico_add_extra_outputs(mcp23017_minimal_picosdk)
