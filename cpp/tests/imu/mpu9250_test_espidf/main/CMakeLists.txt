set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../)

idf_component_register(
    SRCS "main.cpp"
        ${CPP_DIR}/src/chips/imu/MPU9250.cpp
    INCLUDE_DIRS "."
        ${CPP_DIR}/src/connection
        ${CPP_DIR}/src/chips/imu
    REQUIRES driver esp_driver_i2c
)