// Auto-generated STM32Cube example for APA102 (Demo).
// 13-bit effective color depth demonstration using hardware brightness.
// Uses SPIConnectionSTM32Cube for raw SPI Mode 0 (APA102 synchronous protocol).

#include <math.h>
#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SPIConnectionSTM32Cube.h"
#include "APA102.h"

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
// CS is a plain GPIO on PB6 (D10), idle high; SPIConnectionSTM32Cube drives it
// but does not configure it, so it is set up here.
static void spi1_init(uint32_t prescaler, uint32_t polarity, uint32_t phase) {
    __HAL_RCC_SPI1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_5 | GPIO_PIN_7;
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF5_SPI1;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    gpioInit.Pin   = GPIO_PIN_6;
    gpioInit.Mode  = GPIO_MODE_OUTPUT_PP;
    gpioInit.Pull  = GPIO_NOPULL;
    gpioInit.Speed = GPIO_SPEED_FREQ_HIGH;
    HAL_GPIO_WritePin(GPIOB, GPIO_PIN_6, GPIO_PIN_SET);
    HAL_GPIO_Init(GPIOB, &gpioInit);

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

static const size_t N_PIXELS          = 30;
static const int FRAME_MS             = 16;   // ~60 fps
static const int RAINBOW_MS           = 10000;

static void hsv_to_rgb(float h, float s, float v,
                        uint8_t& r, uint8_t& g, uint8_t& b);

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_64, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);

    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_6);  // CS on PB6 (not used by APA102)
    APA102Full strip(connection, N_PIXELS);     // Create APA102 full driver

    // --- 13-bit effective color depth demonstration ---
    // First pass: full hardware brightness (31) for maximum drive current
    // Second pass: hardware brightness 1 (1/31 current) to show hardware vs software dimming

    // --- Pass 1: Full hardware brightness (31) ---
    // Rainbow sweep at hardware brightness 31 uses full 8-bit PWM channels + 5-bit
    // hardware current control = 13-bit effective depth per channel.
    strip.set_brightness(255);                                  // Set global software brightness, (value=0–255) → void
    float hue_offset = 0.0f;
    uint64_t start = HAL_GetTick();
    uint64_t last_print = start;
    while (HAL_GetTick() - start < RAINBOW_MS) {
        for (size_t i = 0; i < N_PIXELS; i++) {
            float h = fmodf(hue_offset + (float)i / N_PIXELS, 1.0f);
            uint8_t r, g, b;
            hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
            strip.set_pixel(i, r, g, b, 31);                    // Set pixel i to rainbow hue at hw brightness 31, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
        }
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                  // applies software brightness scaling then calls connection.write()
        hue_offset = fmodf(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
        uint64_t now = HAL_GetTick();
        if (now - last_print >= 1000) {
            printf("rainbow hw_brightness=31 hue_offset=%.3f\r\n", (double)hue_offset);
            last_print = now;
        }
        uint64_t elapsed = HAL_GetTick() - now;
        if (elapsed < FRAME_MS) HAL_Delay(FRAME_MS - elapsed);
    }

    // --- Pass 2: Low hardware brightness (1) ---
    // Same 8-bit RGB values but hardware brightness=1 (1/31 drive current).
    // Demonstrates hardware current control vs software brightness scaling.
    strip.set_brightness(255);                                  // Set global software brightness, (value=0–255) → void
    hue_offset = 0.0f;
    start = HAL_GetTick();
    last_print = start;
    while (HAL_GetTick() - start < RAINBOW_MS) {
        for (size_t i = 0; i < N_PIXELS; i++) {
            float h = fmodf(hue_offset + (float)i / N_PIXELS, 1.0f);
            uint8_t r, g, b;
            hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
            strip.set_pixel(i, r, g, b, 1);                     // Set pixel i to rainbow hue at hw brightness 1, (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
        }
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                  // applies software brightness scaling then calls connection.write()
        hue_offset = fmodf(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
        uint64_t now = HAL_GetTick();
        if (now - last_print >= 1000) {
            printf("rainbow hw_brightness=1 hue_offset=%.3f\r\n", (double)hue_offset);
            last_print = now;
        }
        uint64_t elapsed = HAL_GetTick() - now;
        if (elapsed < FRAME_MS) HAL_Delay(FRAME_MS - elapsed);
    }

    strip.off();                                                // Turn off all pixels, () → void
    HAL_Delay(1000);
    while (true) HAL_Delay(1000);
}

static void hsv_to_rgb(float h, float s, float v,
                        uint8_t& r, uint8_t& g, uint8_t& b) {
    if (s == 0.0f) { r = g = b = (uint8_t)(v * 255); return; }
    int i = (int)(h * 6.0f);
    float f = h * 6.0f - i;
    uint8_t p  = (uint8_t)(v * (1.0f - s) * 255);
    uint8_t q  = (uint8_t)(v * (1.0f - s * f) * 255);
    uint8_t t  = (uint8_t)(v * (1.0f - s * (1.0f - f)) * 255);
    uint8_t vv = (uint8_t)(v * 255);
    switch (i % 6) {
        case 0: r = vv; g = t;  b = p;  return;
        case 1: r = q;  g = vv; b = p;  return;
        case 2: r = p;  g = vv; b = t;  return;
        case 3: r = p;  g = q;  b = vv; return;
        case 4: r = t;  g = p;  b = vv; return;
        default: r = vv; g = p; b = q;
    }
}
