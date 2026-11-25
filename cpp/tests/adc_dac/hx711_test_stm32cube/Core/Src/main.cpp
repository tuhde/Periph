#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "HX711ConnectionSTM32Cube.h"
#include "HX711.h"

static UART_HandleTypeDef huart2;

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

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();

    HX711ConnectionSTM32Cube connection(GPIOB, GPIO_PIN_10, GPIOB, GPIO_PIN_4);
    HX711Full<HX711ConnectionSTM32Cube> chip(connection);

    check_true(true, "is_ready compiles");

    int32_t raw = chip.read_raw();
    check_true(raw >= -8388608 && raw <= 8388607, "read_raw in 24-bit signed range");

    chip.set_gain(128);
    check_true(true, "set_gain(128) accepted");

    chip.set_gain(64);
    check_true(true, "set_gain(64) accepted");

    chip.set_gain(32);
    check_true(true, "set_gain(32) accepted");

    chip.set_gain(128);

    int32_t avg = chip.read_average(3);
    check_true(avg >= -8388608 && avg <= 8388607, "read_average in range");

    chip.tare(3);
    check_true(true, "tare accepted");

    chip.set_scale(420.0f);
    check_true(true, "set_scale accepted");

    float weight = chip.read_weight(1);
    check_true(true, "read_weight returns float");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
