cmake_minimum_required(VERSION 3.20)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(ina226_complete)

set(CPP_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../..)

target_sources(app PRIVATE
    src/main.cpp
    ${CPP_SRC}/cpp/src/chips/power/INA226.cpp
)

target_include_directories(app PRIVATE
    ${CPP_SRC}/cpp/src/transport
    ${CPP_SRC}/cpp/src/chips/power
    ${CPP_SRC}/zephyr/src/transport
)
