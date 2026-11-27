# Linux host build of the Periph C++ drivers as an installable library.
#
# Builds libperiph.a from every Linux-capable source in cpp/src and installs
# it with its headers, a pkg-config file (periph.pc) and a CMake package
# (find_package(periph) -> periph::periph). Used by the Debian package
# libperiph-dev (packaging/debian); also usable directly:
#
#   cmake -S cpp/linux -B build && cmake --build build && cmake --install build
#
# cpp/CMakeLists.txt is the Zephyr module and is unrelated to this file.
# Needs libgpiod v2 (libgpiod-dev on Debian 13).

cmake_minimum_required(VERSION 3.16)

set(PERIPH_SRC ${CMAKE_CURRENT_LIST_DIR}/../src)
get_filename_component(PERIPH_SRC ${PERIPH_SRC} ABSOLUTE)

# One version for every C++ target: the Arduino metadata the release stamps.
file(STRINGS ${CMAKE_CURRENT_LIST_DIR}/../library.properties _ver REGEX "^version=")
string(REGEX REPLACE "^version=" "" PERIPH_VERSION "${_ver}")

project(periph VERSION ${PERIPH_VERSION} LANGUAGES CXX)

include(GNUInstallDirs)
include(CMakePackageConfigHelpers)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Platform sources and headers that only build for a microcontroller SDK:
# named after the SDK, or the unsuffixed Arduino connections (I2CConnection.h
# etc.), which include an Arduino core header. Periph.h is the Arduino
# umbrella header and pulls those in, so it is excluded as well.
set(PERIPH_PLATFORM_REGEX "(Arduino|ESPIDF|Zephyr|PicoSDK|STM32Cube|FatFsDiskIO)[^/]*$")
file(GLOB_RECURSE _headers ${PERIPH_SRC}/*.h)
set(PERIPH_EXCLUDED_HEADERS Periph.h)
foreach(h ${_headers})
    file(STRINGS ${h} _arduino REGEX "#[ \t]*include[ \t]*<(Arduino|Wire|SPI)\\.h>")
    if(_arduino)
        file(RELATIVE_PATH _rel ${PERIPH_SRC} ${h})
        list(APPEND PERIPH_EXCLUDED_HEADERS ${_rel})
    endif()
endforeach()

file(GLOB PERIPH_SOURCES CONFIGURE_DEPENDS
    ${PERIPH_SRC}/connection/*.cpp
    ${PERIPH_SRC}/discovery/*.cpp
    ${PERIPH_SRC}/chips/*/*.cpp)
list(FILTER PERIPH_SOURCES EXCLUDE REGEX "${PERIPH_PLATFORM_REGEX}")
foreach(h ${PERIPH_EXCLUDED_HEADERS})
    string(REGEX REPLACE "\\.h$" ".cpp" _cpp "${PERIPH_SRC}/${h}")
    list(REMOVE_ITEM PERIPH_SOURCES ${_cpp})
endforeach()

# Drivers include each other by bare file name, so every header directory is
# an include directory -- the same list cpp/scripts/build-all.sh passes.
file(GLOB PERIPH_CHIP_DIRS LIST_DIRECTORIES true RELATIVE ${PERIPH_SRC} ${PERIPH_SRC}/chips/*)
list(FILTER PERIPH_CHIP_DIRS INCLUDE REGEX "^chips/[^/.]+$")
list(SORT PERIPH_CHIP_DIRS)
set(PERIPH_INCLUDE_SUBDIRS connection discovery storage ${PERIPH_CHIP_DIRS})

add_library(periph STATIC ${PERIPH_SOURCES})
add_library(periph::periph ALIAS periph)
target_compile_options(periph PRIVATE -Wall -Wextra)
set_target_properties(periph PROPERTIES POSITION_INDEPENDENT_CODE ON)
# Also the source root, so "chips/light/Si1145.h"-style includes work too.
target_include_directories(periph PUBLIC
    $<BUILD_INTERFACE:${PERIPH_SRC}>
    $<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}/periph>)
set(PERIPH_PC_CFLAGS "-I\${includedir}/periph")
foreach(d ${PERIPH_INCLUDE_SUBDIRS})
    target_include_directories(periph PUBLIC
        $<BUILD_INTERFACE:${PERIPH_SRC}/${d}>
        $<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}/periph/${d}>)
    string(APPEND PERIPH_PC_CFLAGS " -I\${includedir}/periph/${d}")
endforeach()
# Static library: consumers link its dependencies themselves.
target_link_libraries(periph PUBLIC gpiod pthread m)

install(TARGETS periph EXPORT periphTargets
    ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR})
file(GLOB_RECURSE PERIPH_HEADERS RELATIVE ${PERIPH_SRC} ${PERIPH_SRC}/*.h)
list(FILTER PERIPH_HEADERS EXCLUDE REGEX "${PERIPH_PLATFORM_REGEX}")
list(REMOVE_ITEM PERIPH_HEADERS ${PERIPH_EXCLUDED_HEADERS})
foreach(h ${PERIPH_HEADERS})
    get_filename_component(_dir ${h} DIRECTORY)
    install(FILES ${PERIPH_SRC}/${h} DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/periph/${_dir})
endforeach()

configure_file(${CMAKE_CURRENT_LIST_DIR}/periph.pc.in ${CMAKE_CURRENT_BINARY_DIR}/periph.pc @ONLY)
install(FILES ${CMAKE_CURRENT_BINARY_DIR}/periph.pc
    DESTINATION ${CMAKE_INSTALL_LIBDIR}/pkgconfig)

set(PERIPH_CMAKE_DIR ${CMAKE_INSTALL_LIBDIR}/cmake/periph)
install(EXPORT periphTargets NAMESPACE periph:: DESTINATION ${PERIPH_CMAKE_DIR})
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/periphConfig.cmake
    "include(\${CMAKE_CURRENT_LIST_DIR}/periphTargets.cmake)\n")
write_basic_package_version_file(${CMAKE_CURRENT_BINARY_DIR}/periphConfigVersion.cmake
    COMPATIBILITY SameMajorVersion)
install(FILES
    ${CMAKE_CURRENT_BINARY_DIR}/periphConfig.cmake
    ${CMAKE_CURRENT_BINARY_DIR}/periphConfigVersion.cmake
    DESTINATION ${PERIPH_CMAKE_DIR})
