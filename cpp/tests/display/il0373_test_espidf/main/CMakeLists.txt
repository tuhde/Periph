set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)

idf_component_register(
    SRCS "main.cpp"
        ${CPP_DIR}/src/chips/display/IL0373.cpp
    INCLUDE_DIRS "."
        ${CPP_DIR}/src/connection
        ${CPP_DIR}/src/chips/display
        ${CPP_DIR}/tests/display
    REQUIRES esp_driver_gpio esp_driver_spi esp_timer
)
