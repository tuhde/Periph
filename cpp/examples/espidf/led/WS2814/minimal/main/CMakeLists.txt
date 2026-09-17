set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../)

idf_component_register(
    SRCS "main.cpp"
        ${CPP_DIR}/src/chips/led/WS2814.cpp
        ${CPP_DIR}/src/chips/led/NeoPixelRGBWBase.cpp
    INCLUDE_DIRS "."
        ${CPP_DIR}/src/connection
        ${CPP_DIR}/src/chips/led
    REQUIRES driver
)
