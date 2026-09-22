idf_component_register(
    SRCS "main.cpp"
        ${CPP_DIR}/src/chips/led/APA102.cpp
    INCLUDE_DIRS "."
        ${CPP_DIR}/src/connection
        ${CPP_DIR}/src/chips/led
    REQUIRES driver
)