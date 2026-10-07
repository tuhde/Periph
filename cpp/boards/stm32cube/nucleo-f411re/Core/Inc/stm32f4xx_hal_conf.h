/**
 * HAL configuration for the STM32Cube platform's NUCLEO-F411RE reference
 * board, shared by every cpp/examples/stm32cube and every cpp/tests/<category>/<chip>_test_stm32cube
 * project.
 *
 * Trimmed from STMicroelectronics' STM32CubeF4 v1.28.3 template
 * (Projects/STM32F411RE-Nucleo/Templates/Inc/stm32f4xx_hal_conf.h) down to
 * the modules this repo's STM32Cube connection layer actually uses: GPIO
 * (InputPin/OutputPin), I2C, SPI, UART, plus the RCC/CORTEX/PWR/FLASH/DMA/EXTI
 * modules HAL_Init()/SystemClock_Config() and the above depend on
 * internally. Everything else (ADC, CAN, CRC, CRYP, DAC, DCMI, ETH, HASH,
 * I2S, IWDG, LTDC, NAND/NOR/PCCARD/SRAM/SDRAM, PCD/HCD USB, RNG, RTC, SAI,
 * SD, TIM, USART sync mode, IRDA, SMARTCARD, WWDG) is commented out — none
 * of it is reachable from any *ConnectionSTM32Cube.h class, and leaving it
 * enabled would just compile dead HAL source for every example.
 */

#ifndef __STM32F4xx_HAL_CONF_H
#define __STM32F4xx_HAL_CONF_H

#ifdef __cplusplus
extern "C" {
#endif

/* ########################## Module Selection ############################## */
#define HAL_MODULE_ENABLED
/* #define HAL_ADC_MODULE_ENABLED */
/* #define HAL_CAN_MODULE_ENABLED */
/* #define HAL_CRC_MODULE_ENABLED */
/* #define HAL_CRYP_MODULE_ENABLED */
/* #define HAL_DAC_MODULE_ENABLED */
/* #define HAL_DCMI_MODULE_ENABLED */
#define HAL_DMA_MODULE_ENABLED
/* #define HAL_ETH_MODULE_ENABLED */
#define HAL_EXTI_MODULE_ENABLED
#define HAL_FLASH_MODULE_ENABLED
/* #define HAL_NAND_MODULE_ENABLED */
/* #define HAL_NOR_MODULE_ENABLED */
/* #define HAL_PCCARD_MODULE_ENABLED */
/* #define HAL_SRAM_MODULE_ENABLED */
/* #define HAL_SDRAM_MODULE_ENABLED */
/* #define HAL_HASH_MODULE_ENABLED */
#define HAL_GPIO_MODULE_ENABLED
#define HAL_I2C_MODULE_ENABLED
/* #define HAL_I2S_MODULE_ENABLED */
/* #define HAL_IWDG_MODULE_ENABLED */
/* #define HAL_LTDC_MODULE_ENABLED */
#define HAL_PWR_MODULE_ENABLED
#define HAL_RCC_MODULE_ENABLED
/* #define HAL_RNG_MODULE_ENABLED */
/* #define HAL_RTC_MODULE_ENABLED */
/* #define HAL_SAI_MODULE_ENABLED */
/* #define HAL_SD_MODULE_ENABLED */
#define HAL_SPI_MODULE_ENABLED
/* #define HAL_TIM_MODULE_ENABLED */
#define HAL_UART_MODULE_ENABLED
/* #define HAL_USART_MODULE_ENABLED */
/* #define HAL_IRDA_MODULE_ENABLED */
/* #define HAL_SMARTCARD_MODULE_ENABLED */
/* #define HAL_WWDG_MODULE_ENABLED */
#define HAL_CORTEX_MODULE_ENABLED
/* #define HAL_PCD_MODULE_ENABLED */
/* #define HAL_HCD_MODULE_ENABLED */

/* ########################## Oscillator Values adaptation ################## */
#if !defined (HSE_VALUE)
#define HSE_VALUE              8000000U   /* NUCLEO-F411RE's ST-LINK MCO, unused: SystemClock_Config() runs from HSI */
#endif

#if !defined (HSE_STARTUP_TIMEOUT)
#define HSE_STARTUP_TIMEOUT    100U
#endif

#if !defined (HSI_VALUE)
#define HSI_VALUE              16000000U
#endif

#if !defined (LSI_VALUE)
#define LSI_VALUE              32000U
#endif

#if !defined (LSE_VALUE)
#define LSE_VALUE              32768U
#endif

#if !defined (LSE_STARTUP_TIMEOUT)
#define LSE_STARTUP_TIMEOUT    5000U
#endif

#if !defined (EXTERNAL_CLOCK_VALUE)
#define EXTERNAL_CLOCK_VALUE   12288000U
#endif

/* ########################### System Configuration ########################## */
#define  VDD_VALUE                    3300U
#define  TICK_INT_PRIORITY            0x0FU
#define  USE_RTOS                     0U
#define  PREFETCH_ENABLE              1U
#define  INSTRUCTION_CACHE_ENABLE     1U
#define  DATA_CACHE_ENABLE            1U

/* ######################### Register Callback feature ####################### */
#define  USE_HAL_I2C_REGISTER_CALLBACKS   0U
#define  USE_HAL_SPI_REGISTER_CALLBACKS   0U
#define  USE_HAL_UART_REGISTER_CALLBACKS  0U

/* ################## Ethernet peripheral configuration ###################### */
/* ################## SPI peripheral configuration ########################### */
#define USE_SPI_CRC                   0U

/* Includes for the modules enabled above. */
#include "stm32f4xx_hal_rcc.h"
#include "stm32f4xx_hal_rcc_ex.h"
#include "stm32f4xx_hal_gpio.h"
#include "stm32f4xx_hal_dma.h"
#include "stm32f4xx_hal_dma_ex.h"
#include "stm32f4xx_hal_cortex.h"
#include "stm32f4xx_hal_flash.h"
#include "stm32f4xx_hal_flash_ex.h"
#include "stm32f4xx_hal_exti.h"
#include "stm32f4xx_hal_pwr.h"
#include "stm32f4xx_hal_pwr_ex.h"
#include "stm32f4xx_hal_i2c.h"
#include "stm32f4xx_hal_i2c_ex.h"
#include "stm32f4xx_hal_spi.h"
#include "stm32f4xx_hal_uart.h"

#ifdef  USE_FULL_ASSERT
#define assert_param(expr) ((expr) ? (void)0U : Error_Handler())
void Error_Handler(void);
#else
#define assert_param(expr) ((void)0U)
#endif

#ifdef __cplusplus
}
#endif

#endif /* __STM32F4xx_HAL_CONF_H */
