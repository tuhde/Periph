#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "NeoPixelConnectionSTM32Cube.h"
#include "WS2812B.h"

static UART_HandleTypeDef huart2;
static SPI_HandleTypeDef hspi1;

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

// SPI1 on the Arduino header: PA5=SCK (D13), PA6=MISO (D12), PA7=MOSI (D11).
// SPI1 is on APB2 (100 MHz), so the baud rate is 100 MHz / prescaler.
static void spi1_init(uint32_t prescaler, uint32_t polarity, uint32_t phase) {
    __HAL_RCC_SPI1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_5 | GPIO_PIN_7;
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF5_SPI1;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    hspi1.Instance               = SPI1;
    hspi1.Init.Mode              = SPI_MODE_MASTER;
    hspi1.Init.Direction         = SPI_DIRECTION_2LINES;
    hspi1.Init.DataSize          = SPI_DATASIZE_8BIT;
    hspi1.Init.CLKPolarity       = polarity;
    hspi1.Init.CLKPhase          = phase;
    hspi1.Init.NSS               = SPI_NSS_SOFT;
    hspi1.Init.BaudRatePrescaler = prescaler;
    hspi1.Init.FirstBit          = SPI_FIRSTBIT_MSB;
    hspi1.Init.TIMode            = SPI_TIMODE_DISABLE;
    hspi1.Init.CRCCalculation    = SPI_CRCCALCULATION_DISABLE;
    HAL_SPI_Init(&hspi1);
}

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

static void check_eq_u8(const char *label, uint8_t got, uint8_t expected) {
    if (got == expected) { printf("PASS %s\r\n", label); passed++; }
    else { printf("FAIL %s: got %u, expected %u\r\n", label, got, expected); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    // SPI1 sits on APB2 (100 MHz) and cannot produce the 2.4 MHz NeoPixelConnectionSTM32Cube is specified for;
    // 100 MHz / 32 = 3.125 MHz is the nearest rate. Bit and reset timing are therefore slightly off the
    // WS2812B datasheet values and are unverified on hardware.
    // NeoPixel DIN connects to MOSI (PA7, D11); SCK, MISO and CS are unused by the strip.
    spi1_init(SPI_BAUDRATEPRESCALER_32, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);
    NeoPixelConnectionSTM32Cube connection(&hspi1);

    // --- WS2812BMinimal ---
    {
        WS2812BMinimal strip(connection, 8);

        strip.fill(255, 0, 0);
        check_true(true, "fill(255,0,0) accepted");

        strip.fill(0, 255, 0);
        check_true(true, "fill(0,255,0) accepted");

        strip.off();
        check_true(true, "off() accepted");
    }

    // --- WS2812BFull ---
    {
        WS2812BFull strip(connection, 8);

        check_eq_u8("default brightness is 255", strip.get_brightness(), 255);

        strip.set_pixel(0, 255, 0, 0);
        strip.show();
        check_true(true, "set_pixel + show accepted");

        strip.set_brightness(128);
        check_eq_u8("brightness setter", strip.get_brightness(), 128);
        strip.show();
        check_true(true, "show() with brightness=128 accepted");

        strip.set_brightness(255);

        strip.rotate(1);
        strip.show();
        check_true(true, "rotate + show accepted");

        strip.fill_hsv(0.0f, 1.0f, 1.0f);
        check_true(true, "fill_hsv accepted");

        strip.off();
        check_true(true, "off() on Full accepted");
    }

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
