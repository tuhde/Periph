set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)

idf_component_register(
    SRCS "main.cpp"
        ${CPP_DIR}/src/chips/humidity/DHT11.cpp
    INCLUDE_DIRS "."
        ${CPP_DIR}/src/transport
        ${CPP_DIR}/src/chips/humidity
    REQUIRES driver esp_timer
)
