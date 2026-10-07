# NUCLEO-F411RE board files

Reference board for the STM32Cube platform (`specs/feature_stm32cube_platform.md`).
MCU: STM32F411RET6 (Cortex-M4, 512 KB flash, 128 KB RAM).

| File | Source |
|---|---|
| `STM32F411RETX_FLASH.ld` | Vendored verbatim from STM32CubeF4 v1.28.3, `Projects/STM32F411RE-Nucleo/Templates/STM32CubeIDE/STM32F411RETX_FLASH.ld` |
| `startup_stm32f411xe.s` | Vendored verbatim from STM32CubeF4 v1.28.3, `Projects/STM32F411RE-Nucleo/Templates/STM32CubeIDE/Example/Startup/startup_stm32f411retx.s` |
| `Core/Inc/stm32f4xx_hal_conf.h` | Trimmed from the same package's `Templates/Inc/stm32f4xx_hal_conf.h` — only the HAL modules this repo's connection layer uses are enabled (see the file's own header comment) |
| `Core/Inc/system_clock_config.h`, `Core/Src/system_clock_config.c` | Adapted from the same package's `Templates/Src/main.c` `SystemClock_Config()` (100 MHz PLL from HSI) |

CMSIS device headers and the STM32F4 HAL driver sources themselves are **not**
vendored here — they come from `$STM32CUBE_FW_PATH` at build time (see
`TOOLCHAINS.md`), the same way `PICO_SDK_PATH`/`ZEPHYR_BASE`/`IDF_PATH`
supply the SDK for the other embedded platforms.
