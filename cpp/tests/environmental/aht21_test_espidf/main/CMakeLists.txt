set(CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)

# esp_driver_i2c only exists as its own requestable component from
# ESP-IDF v6.0 onward - before that, driver/i2c_master.h ships inside the
# monolithic `driver` component and requiring esp_driver_i2c explicitly
# fails component resolution (this repo's CI pins v5.2.7).
set(EXTRA_REQUIRES "")
if(IDF_VERSION_MAJOR GREATER_EQUAL 6)
    set(EXTRA_REQUIRES esp_driver_i2c)
endif()

idf_component_register(
    SRCS "main.cpp"
        ${CPP_DIR}/src/chips/environmental/AHT21.cpp
    INCLUDE_DIRS "."
        ${CPP_DIR}/src/connection
        ${CPP_DIR}/src/chips/environmental
    REQUIRES driver ${EXTRA_REQUIRES}
)
