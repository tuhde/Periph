cmake_minimum_required(VERSION 3.20)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(am2315_complete_zephyr)

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../)

target_sources(app PRIVATE
    main.cpp
    ${CPP_DIR}/src/chips/environmental/AM2315.cpp
)
target_include_directories(app PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/environmental
)
