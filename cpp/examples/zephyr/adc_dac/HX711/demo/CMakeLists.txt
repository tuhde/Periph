cmake_minimum_required(VERSION 3.20)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(hx711_demo_zephyr)

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../)

target_sources(app PRIVATE
    main.cpp
)
target_include_directories(app PRIVATE
    ${CPP_DIR}/src/transport
    ${CPP_DIR}/src/chips/adc_dac
)
