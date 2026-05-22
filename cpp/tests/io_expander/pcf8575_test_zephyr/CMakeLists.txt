cmake_minimum_required(VERSION 3.20)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(pcf8575_test_zephyr)

set(CPP_DIR $ENV{CPP_DIR}/src)

target_sources(app PRIVATE
    src/main.cpp
    ${CPP_DIR}/chips/io_expander/PCF8575.cpp
)
target_include_directories(app PRIVATE
    ${CPP_DIR}/transport
    ${CPP_DIR}/chips/io_expander
)