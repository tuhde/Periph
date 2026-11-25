#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include <cmath>
#include "UARTConnectionSTM32Cube.h"
#include "NEO6.h"

static UART_HandleTypeDef huart2;
static UART_HandleTypeDef huart1;

// SysTick_Handler overrides the weak default in startup_stm32f411xe.s —
// without this, HAL_GetTick()/HAL_Delay() never advance.
extern "C" void SysTick_Handler(void) {
    HAL_IncTick();
}

// Retargets printf() to USART2 (PA2/PA3), which NUCLEO-F411RE's on-board
// ST-LINK exposes as a USB virtual COM port at 115200 baud.
extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(ptr), static_cast<uint16_t>(len), HAL_MAX_DELAY);
    return len;
}

static void gpio_clocks_init(void) {
    __HAL_RCC_GPIOA_CLK_ENABLE();
    __HAL_RCC_GPIOB_CLK_ENABLE();
    __HAL_RCC_GPIOC_CLK_ENABLE();
}

static void uart2_init(void) {
    __HAL_RCC_USART2_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_2 | GPIO_PIN_3;  // PA2=TX, PA3=RX
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_LOW;
    gpioInit.Alternate = GPIO_AF7_USART2;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    huart2.Instance          = USART2;
    huart2.Init.BaudRate     = 115200;
    huart2.Init.WordLength   = UART_WORDLENGTH_8B;
    huart2.Init.StopBits     = UART_STOPBITS_1;
    huart2.Init.Parity       = UART_PARITY_NONE;
    huart2.Init.Mode         = UART_MODE_TX_RX;
    huart2.Init.HwFlowCtl    = UART_HWCONTROL_NONE;
    huart2.Init.OverSampling = UART_OVERSAMPLING_16;
    HAL_UART_Init(&huart2);
}

// USART1 on PA9=TX (D8) / PA10=RX (D2). USART2 is taken by the ST-LINK virtual COM port.
static void uart1_init(uint32_t baud) {
    __HAL_RCC_USART1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_9 | GPIO_PIN_10;
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_PULLUP;
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF7_USART1;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    huart1.Instance          = USART1;
    huart1.Init.BaudRate     = baud;
    huart1.Init.WordLength   = UART_WORDLENGTH_8B;
    huart1.Init.StopBits     = UART_STOPBITS_1;
    huart1.Init.Parity       = UART_PARITY_NONE;
    huart1.Init.Mode         = UART_MODE_TX_RX;
    huart1.Init.HwFlowCtl    = UART_HWCONTROL_NONE;
    huart1.Init.OverSampling = UART_OVERSAMPLING_16;
    HAL_UART_Init(&huart1);
}

// Requires a NEO-6 module wired to UART with a clear sky view. Achieving an
// actual fix needs an outdoor antenna and can take up to ~26 s (cold start);
// this test only requires that well-typed values come back, not a fix.

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\r\n", label); passed++; }
    else           { printf("FAIL %s\r\n", label); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    uart1_init(9600);
    UARTConnectionSTM32Cube connection(&huart1);
    NEO6Minimal gps(connection);

    check_true("fix() starts at 0", gps.fix() == 0);
    check_true("latitude() starts at NAN", std::isnan(gps.latitude()));

    // Listen for NMEA sentences for 30 s of wall-clock time. update() can block for the UART's 1 s read
    // timeout when no module is attached, so a fixed iteration count could run for the better part of an hour.
    const uint32_t listenStart = HAL_GetTick();
    while (HAL_GetTick() - listenStart < 30000) {
        gps.update();
    }

    check_true("fix() is a valid quality code", gps.fix() == 0 || gps.fix() == 1 || gps.fix() == 2);
    check_true("satellites() is a non-negative int", gps.satellites() >= 0);
    if (gps.fix() > 0) {
        check_true("latitude() in range once fixed", gps.latitude() >= -90.0f && gps.latitude() <= 90.0f);
        check_true("longitude() in range once fixed", gps.longitude() >= -180.0f && gps.longitude() <= 180.0f);
        check_true("altitude() is populated once fixed", !std::isnan(gps.altitude()));
    } else {
        printf("note: no fix acquired during the test window (needs sky view)\r\n");
    }

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
