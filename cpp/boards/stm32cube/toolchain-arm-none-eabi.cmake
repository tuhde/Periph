# ARM GCC CMake toolchain file for the STM32Cube platform (NUCLEO-F411RE,
# Cortex-M4 + FPU). `include()`d by every cpp/examples/stm32cube and
# cpp/tests/*_test_stm32cube CMakeLists.txt BEFORE project(), the same
# early-inclusion pattern Pico SDK's pico_sdk_init.cmake uses — CMake
# probes the compiler at the first project() call, so the compiler and
# flags below must already be set by then.
#
# Requires `arm-none-eabi-gcc`/`g++` on PATH (see TOOLCHAINS.md).

set(CMAKE_SYSTEM_NAME Generic)
set(CMAKE_SYSTEM_PROCESSOR arm)

set(CMAKE_C_COMPILER   arm-none-eabi-gcc)
set(CMAKE_CXX_COMPILER arm-none-eabi-g++)
set(CMAKE_ASM_COMPILER arm-none-eabi-gcc)
set(CMAKE_OBJCOPY      arm-none-eabi-objcopy)
set(CMAKE_SIZE         arm-none-eabi-size)

# A freestanding cross toolchain can't link a full test executable at
# detection time (no _exit/semihosting target) — build a static lib instead.
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)

set(STM32CUBE_CPU_FLAGS "-mcpu=cortex-m4 -mfpu=fpv4-sp-d16 -mfloat-abi=hard -mthumb")

set(CMAKE_C_FLAGS   "${STM32CUBE_CPU_FLAGS} -DUSE_HAL_DRIVER -DSTM32F411xE -Wall -Wextra -ffunction-sections -fdata-sections")
set(CMAKE_CXX_FLAGS "${STM32CUBE_CPU_FLAGS} -DUSE_HAL_DRIVER -DSTM32F411xE -Wall -Wextra -ffunction-sections -fdata-sections -fno-exceptions -fno-rtti")
set(CMAKE_ASM_FLAGS "${STM32CUBE_CPU_FLAGS} -x assembler-with-cpp")

set(STM32CUBE_LINKER_SCRIPT "${CMAKE_CURRENT_LIST_DIR}/nucleo-f411re/STM32F411RETX_FLASH.ld")
set(CMAKE_EXE_LINKER_FLAGS
    "${STM32CUBE_CPU_FLAGS} -T${STM32CUBE_LINKER_SCRIPT} -Wl,--gc-sections -Wl,-Map=\${CMAKE_PROJECT_NAME}.map --specs=nosys.specs")

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
