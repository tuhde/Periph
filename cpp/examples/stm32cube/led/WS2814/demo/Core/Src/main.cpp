#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "NeoPixelConnectionSTM32Cube.h"
#include "WS2814.h"

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

static const size_t N_PIXELS          = 30;
static const unsigned long FRAME_MS   = 33;
static const unsigned long RAINBOW_MS = 5000;
static const unsigned long FLASH_MS   = 2000;
static const unsigned long DIM_MS     = 2000;

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
    WS2814Full strip(connection, N_PIXELS);

    while (true) {
        // --- Rainbow rotation using RGB channels (white=0). Each pixel is
        //     assigned a hue offset by its position; the offset advances
        //     each frame so the rainbow rotates continuously around the
        //     strip. Demonstrates WS2814's identity RGBW wire order (no
        //     reorder), unlike SK6812RGBW's GRBW order. Runs at ~30 fps
        //     for 5 seconds. ---
        float hue_offset = 0.0f;
        unsigned long start = HAL_GetTick();
        unsigned long last_print = start;
        while (HAL_GetTick() - start < RAINBOW_MS) {
            for (size_t i = 0; i < N_PIXELS; i++) {
                float h = fmod(hue_offset + (float)i / N_PIXELS, 1.0f);
                uint8_t r, g, b;
                neopixel_hsv_to_rgb(h, 1.0f, 1.0f, r, g, b);
                strip.set_pixel(i, r, g, b, 0);    // Set pixel i to rainbow hue (w=0), (index=0–n-1, r=0–255, g=0–255, b=0–255, w=0–255) → void
            }
            strip.show();                          // Transmit buffer to strip, () → void
            hue_offset = fmod(hue_offset + 1.0f / (N_PIXELS * 2), 1.0f);
            unsigned long now = HAL_GetTick();
            if (now - last_print >= 1000) {
                printf("mode=rainbow brightness=%u\r\n", strip.get_brightness());
                last_print = now;
            }
            unsigned long elapsed = HAL_GetTick() - now;
            if (elapsed < FRAME_MS) HAL_Delay(FRAME_MS - elapsed);
        }

        // --- Warm white at full brightness for 2 seconds. ---
        strip.fill(255, 200, 150, 255);            // Fill all pixels warm white, (r=0–255, g=0–255, b=0–255, w=0–255) → void
        start = HAL_GetTick();
        while (HAL_GetTick() - start < FLASH_MS) {
            printf("mode=warm-white brightness=%u\r\n", strip.get_brightness());
            HAL_Delay(100);
        }

        // --- Dim warm white to 50% for 2 seconds. ---
        strip.set_brightness(128);                 // Set global brightness, (value=0–255) → void
        strip.show();                              // Transmit buffer to strip, () → void
        start = HAL_GetTick();
        while (HAL_GetTick() - start < DIM_MS) {
            printf("mode=warm-white-dimmed brightness=%u\r\n", strip.get_brightness());
            HAL_Delay(100);
        }

        // --- Cycle to cool white at full brightness. ---
        strip.set_brightness(255);                 // Set global brightness, (value=0–255) → void
        strip.fill(200, 210, 255, 255);            // Fill all pixels cool white, (r=0–255, g=0–255, b=0–255, w=0–255) → void
        printf("mode=cool-white brightness=%u\r\n", strip.get_brightness());
        HAL_Delay(1000);
    }

}
