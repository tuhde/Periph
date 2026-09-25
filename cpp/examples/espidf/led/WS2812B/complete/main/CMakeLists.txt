set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../..)

idf_component_register(
    SRCS "main.cpp"
        ${CPP_DIR}/src/chips/led/WS2812B.cpp
        ${CPP_DIR}/src/chips/led/NeoPixelRGBBase.cpp
    INCLUDE_DIRS "."
        ${CPP_DIR}/src/connection
        ${CPP_DIR}/src/chips/led
    REQUIRES esp_driver_spi esp_driver_rmt
)
