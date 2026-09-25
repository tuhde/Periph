set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../..)

idf_component_register(
    SRCS "main.cpp"
        ${CPP_DIR}/src/chips/gyroscope/L3gd20h.cpp
    INCLUDE_DIRS "."
        ${CPP_DIR}/src/connection
        ${CPP_DIR}/src/chips/gyroscope
    REQUIRES esp_driver_i2c
)