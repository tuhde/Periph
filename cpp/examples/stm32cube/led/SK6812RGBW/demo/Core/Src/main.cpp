#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "NeoPixelConnectionSTM32Cube.h"
#include "SK6812RGBW.h"

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
    SK6812RGBWFull strip(connection, /*n_pixels=*/8);

    static const size_t N_PIXELS          = 30;
    static const unsigned long FRAME_MS   = 33;   // ~30 fps
    static const unsigned long RAINBOW_MS = 10000;
    static const unsigned long WARM_MS    = 2000;
    static const unsigned long WARM_HALF  = 100;  // 5 Hz

    strip.set_brightness(180);                 // Set global brightness, (value=0–255) → void
    while (true) {

    // --- Rainbow rotation: each pixel is assigned a hue offset by its position;
    //     the offset advances each frame so the rainbow rotates around the strip.
    //     RGB channels only (w=0) for 10 seconds at ~30 fps. ---
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
            printf("rainbow hue_offset=");
            printf("%.3f\r\n", hue_offset);
            last_print = now;
        }
        unsigned long elapsed = HAL_GetTick() - now;
        if (elapsed < FRAME_MS) HAL_Delay(FRAME_MS - elapsed);
    }

    // --- Warm-white strobe: showcases the dedicated white element.
    //     All four channels active (r=255, g=200, b=150, w=255) gives a warm,
    //     high-CRI white; toggling at 5 Hz for 2 seconds draws the eye to the
    //     difference between mixed-RGB white and the native W element. ---
    strip.set_brightness(255);                 // Set global brightness, (value=0–255) → void
    strip.fill(255, 200, 150, 255);            // Pre-load warm white (RGB+W) into buffer, (r=0–255, g=0–255, b=0–255, w=0–255) → void
    start = HAL_GetTick();
    bool state = true;
    while (HAL_GetTick() - start < WARM_MS) {
        strip.set_brightness(state ? 255 : 0); // Toggle brightness on/off, (value=0–255) → void
        strip.show();                          // Transmit buffer to strip, () → void
        state = !state;
        HAL_Delay(WARM_HALF);
    }

    // --- Return to continuous rainbow ---
    strip.set_brightness(180);                 // Set global brightness, (value=0–255) → void
        HAL_Delay(10);
    }

}
