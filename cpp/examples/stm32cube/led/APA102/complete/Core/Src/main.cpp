// Auto-generated STM32Cube example for APA102 (Complete).
// Uses SPIConnectionSTM32Cube for raw SPI Mode 0 (APA102 synchronous protocol).

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
    spi1_init(SPI_BAUDRATEPRESCALER_64, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);

    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_6);  // CS on PB6 (not used by APA102)
    APA102Full strip(connection, 8);            // Create APA102 full driver

    while (true) {
        // fill — set all pixels and send immediately
        strip.fill(255, 0, 0);                                  // Fill all pixels with one colour, (r=0–255, g=0–255, b=0–255) → void
                                                                 // stores brightness/B/G/R in buffer and calls connection.write()
        HAL_Delay(500);

        // set individual pixels then show
        strip.set_pixel(0, 255, 0, 0);                          // Set pixel 0 to red (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        strip.set_pixel(1, 0, 255, 0);                          // Set pixel 1 to green (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        strip.set_pixel(2, 0, 0, 255);                          // Set pixel 2 to blue (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        HAL_Delay(500);

        // set_pixels — write multiple pixels at once
        uint8_t colors[] = {
            255, 128, 0,   128, 0, 255,   0, 255, 128,
            255, 255, 0,   0, 255, 255,   255, 0, 255,
            128, 128, 128, 255, 255, 255
        };
        strip.set_pixels(colors, 8, false);                     // Set pixels from flat array of (r,g,b), (colors=uint8_t[], count, has_brightness=false) → void
                                                                 // writes entries sequentially from pixel 0; ignores extras beyond strip length
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        HAL_Delay(500);

        // set_pixels with per-pixel hardware brightness
        uint8_t colors_bright[] = {
            255, 0, 0, 31,   255, 0, 0, 16,   255, 0, 0, 8,   255, 0, 0, 4,
            0, 255, 0, 31,   0, 255, 0, 16,   0, 255, 0, 8,   0, 255, 0, 4
        };
        strip.set_pixels(colors_bright, 8, true);               // Set pixels from flat array of (r,g,b,brightness), (colors=uint8_t[], count, has_brightness=true) → void
                                                                 // writes entries sequentially from pixel 0; ignores extras beyond strip length
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        HAL_Delay(500);

        // brightness — global software scale applied at show() time
        strip.set_brightness(64);                               // Set global software brightness, (value=0–255) → void
                                                                 // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        HAL_Delay(500);
        strip.set_brightness(255);                              // Set global software brightness, (value=0–255) → void
                                                                 // stored RGB value is scaled: sent = stored * brightness / 255; hardware brightness byte unchanged

        // fill_hsv — fill all pixels from HSV colour
        strip.fill_hsv(0.0f, 1.0f, 1.0f);                       // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                 // converts HSV to RGB then calls fill(); hue 0.0 = red
        HAL_Delay(500);
        strip.fill_hsv(0.333f, 1.0f, 1.0f);                     // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                 // converts HSV to RGB then calls fill(); hue 0.333 = green
        HAL_Delay(500);
        strip.fill_hsv(0.667f, 1.0f, 1.0f);                     // Fill all pixels with HSV colour and send, (h=0.0–1.0, s=0.0–1.0, v=0.0–1.0) → void
                                                                 // converts HSV to RGB then calls fill(); hue 0.667 = blue
        HAL_Delay(500);

        // rotate — shift pixel buffer by N positions
        strip.set_pixel(0, 255, 0, 0);                          // Set pixel 0 in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        for (size_t i = 1; i < 8; i++) {
            strip.set_pixel(i, 0, 0, 0);                        // Set pixel i in buffer (no send), (index=0–n-1, r=0–255, g=0–255, b=0–255, pixel_brightness=0–31) → void
                                                                 // writes brightness, B, G, R bytes into internal buffer at position index*4
        }
        strip.show();                                           // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
        HAL_Delay(500);
        for (int i = 0; i < 7; i++) {
            strip.rotate(1);                                    // Rotate pixel buffer left, (steps=1) → void
                                                                 // shifts buffer by steps pixel positions; wraps around; does not send
            strip.show();                                       // Transmit buffer to strip, () → void
                                                                 // applies software brightness scaling then calls connection.write()
            HAL_Delay(200);
        }

        strip.off();                                            // Turn off all pixels, () → void
                                                                 // equivalent to fill(0, 0, 0)
        HAL_Delay(1000);
    }

}
