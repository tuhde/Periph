set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../..)

idf_component_register(
    SRCS "main.cpp"
        ${CPP_DIR}/src/chips/other/Mpr121.cpp
    INCLUDE_DIRS "."
        ${CPP_DIR}/src/connection
        ${CPP_DIR}/src/chips/other
    REQUIRES esp_driver_i2c
)
