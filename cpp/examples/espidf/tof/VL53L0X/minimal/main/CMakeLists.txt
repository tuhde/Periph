set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../..)

idf_component_register(
    SRCS "main.cpp"
        ${CPP_DIR}/src/chips/tof/VL53L0X.cpp
        ${CPP_DIR}/src/chips/tof/VL53Base.cpp
    INCLUDE_DIRS "."
        ${CPP_DIR}/src/connection
        ${CPP_DIR}/src/chips/tof
    REQUIRES esp_driver_i2c
)
