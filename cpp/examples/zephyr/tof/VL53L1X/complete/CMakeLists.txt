cmake_minimum_required(VERSION 3.20)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(vl53l1x_complete_zephyr)

set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../)

target_sources(app PRIVATE
    main.cpp
    ${CPP_DIR}/src/chips/tof/VL53L1X.cpp
    ${CPP_DIR}/src/chips/tof/VL53Base.cpp
)
target_include_directories(app PRIVATE
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/tof
)
