cmake_minimum_required(VERSION 3.20)

# BOARD_DIR / CPP_DIR: relative to cpp/examples/stm32cube/<category>/<Chip>/<tier>/
set(BOARD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../boards/stm32cube)
set(CPP_DIR    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

include(${BOARD_DIR}/toolchain-arm-none-eabi.cmake)

project(ade7953_minimal_stm32cube C CXX ASM)

if(NOT DEFINED ENV{STM32CUBE_FW_PATH})
    message(FATAL_ERROR "STM32CUBE_FW_PATH is not set — see TOOLCHAINS.md")
endif()
set(FW_DIR $ENV{STM32CUBE_FW_PATH})

add_executable(ade7953_minimal_stm32cube
    Core/Src/main.cpp
    ${BOARD_DIR}/nucleo-f411re/startup_stm32f411xe.s
    ${BOARD_DIR}/nucleo-f411re/Core/Src/system_clock_config.c
    ${CPP_DIR}/src/chips/power/ADE7953.cpp
    ${FW_DIR}/Drivers/CMSIS/Device/ST/STM32F4xx/Source/Templates/system_stm32f4xx.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_cortex.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_rcc.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_rcc_ex.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_gpio.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_dma.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_pwr.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_pwr_ex.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_flash.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_flash_ex.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_flash_ramfunc.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_exti.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_i2c.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_i2c_ex.c
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Src/stm32f4xx_hal_uart.c
)

target_include_directories(ade7953_minimal_stm32cube PRIVATE
    ${BOARD_DIR}/nucleo-f411re/Core/Inc
    ${CPP_DIR}/src/connection
    ${CPP_DIR}/src/chips/power
    ${FW_DIR}/Drivers/CMSIS/Device/ST/STM32F4xx/Include
    ${FW_DIR}/Drivers/CMSIS/Core/Include
    ${FW_DIR}/Drivers/STM32F4xx_HAL_Driver/Inc
)

target_link_options(ade7953_minimal_stm32cube PRIVATE -Wl,--print-memory-usage)

add_custom_command(TARGET ade7953_minimal_stm32cube POST_BUILD
    COMMAND ${CMAKE_SIZE} ade7953_minimal_stm32cube
    COMMAND ${CMAKE_OBJCOPY} -O ihex ade7953_minimal_stm32cube ade7953_minimal_stm32cube.hex
    COMMAND ${CMAKE_OBJCOPY} -O binary ade7953_minimal_stm32cube ade7953_minimal_stm32cube.bin
)
