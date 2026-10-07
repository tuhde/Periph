#pragma once

#ifdef __cplusplus
extern "C" {
#endif

/** @brief Configure SYSCLK to 100 MHz (PLL from the internal 16 MHz HSI).
 *
 * Shared by every cpp/examples/stm32cube and every cpp/tests/<category>/<chip>_test_stm32cube
 * main() so the clock tree is identical across the whole platform — adapted
 * from STMicroelectronics' STM32CubeF4 v1.28.3 Nucleo-F411RE template
 * (Projects/STM32F411RE-Nucleo/Templates/Src/main.c). Deliberately HSI-based
 * rather than HSE-based: it needs no external crystal/MCO wiring, so the
 * same call works on any NUCLEO-F411RE out of the box.
 *
 * Must be called once, after HAL_Init(), before using any peripheral.
 */
void SystemClock_Config(void);

#ifdef __cplusplus
}
#endif
