# Zephyr module build file — see zephyr/module.yml.
#
# Exposes the chip drivers (src/chips/**) as a "periph" library. Chip
# drivers depend only on the Connection/InputPin/OutputPin abstractions
# (src/connection/Connection.h) and are therefore platform-agnostic; the
# Zephyr-specific connection implementations (src/connection/*Zephyr.h) are
# header-only and need no compiled sources of their own.
#
# The unsuffixed connection files (I2CConnection.cpp, SPIConnection.cpp,
# etc.) are the Arduino implementations (they pull in <Wire.h>/<SPI.h>) and
# must NOT be compiled into this module.

zephyr_library_named(periph)

file(GLOB_RECURSE PERIPH_CHIP_SOURCES CONFIGURE_DEPENDS
    ${CMAKE_CURRENT_LIST_DIR}/src/chips/*.cpp
)
target_sources(periph PRIVATE ${PERIPH_CHIP_SOURCES})

# Every chip category directory is added so app code can `#include
# "<Chip>.h"` without qualifying the category (matches every example under
# cpp/examples/zephyr/).
file(GLOB PERIPH_CHIP_CATEGORY_DIRS CONFIGURE_DEPENDS
    ${CMAKE_CURRENT_LIST_DIR}/src/chips/*
)
foreach(dir ${PERIPH_CHIP_CATEGORY_DIRS})
    if(IS_DIRECTORY ${dir})
        target_include_directories(periph PUBLIC ${dir})
    endif()
endforeach()

target_include_directories(periph PUBLIC
    ${CMAKE_CURRENT_LIST_DIR}/src/connection
)
