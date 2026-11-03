// SSD1331 demo example: Waveshare 0.95inch RGB OLED on a NUCLEO-F411RE.
//
// Demo: a colour test card — eight colour bars over a gray ramp, a frame, then a
// bouncing marker drawn with the controller's own line/rectangle commands and a slow scroll.
//
// Wiring (same pins as the Waveshare e-Paper Shield, no BUSY line, 3.3 V logic):
//   DIN  = PA7 (D11, SPI1 MOSI)     CLK  = PA5 (D13, SPI1 SCK)
//   CS   = PB6 (D10)                DC   = PC7 (D9)
//   RST  = PA9 (D8)                 VCC  = 3V3      GND = GND
// Progress goes to USART2 (ST-LINK virtual COM port, 115200 8N1).
#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "DisplayConnectionSTM32Cube.h"
#include "OutputPinSTM32Cube.h"
#include "InputPinSTM32Cube.h"
#include "SSD1331.h"

static UART_HandleTypeDef huart2;
static SPI_HandleTypeDef hspi1;

// 96 x 64 pixels, RGB565 (2 bytes each, high byte first).
static uint8_t frameBuffer[96 * 64 * 2];

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

// SPI1 write-only on the Arduino header: PA5=SCK (D13), PA7=MOSI (D11). Mode 0,
// MSB first. SPI1 is on APB2 (100 MHz): /32 gives 3.1 MHz; the SSD1331 allows
// up to 6.6 MHz writes (150 ns cycle), the vendor demo ran at about 3 MHz.
static void spi1_init(void) {
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
    hspi1.Init.CLKPolarity       = SPI_POLARITY_LOW;
    hspi1.Init.CLKPhase          = SPI_PHASE_1EDGE;
    hspi1.Init.NSS               = SPI_NSS_SOFT;
    hspi1.Init.BaudRatePrescaler = SPI_BAUDRATEPRESCALER_32;
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
    spi1_init();

    OutputPinSTM32Cube cs(GPIOB, GPIO_PIN_6);       // CS# pin, (port, pin)
    OutputPinSTM32Cube dc(GPIOC, GPIO_PIN_7);       // D/C# pin, (port, pin)
    OutputPinSTM32Cube res(GPIOA, GPIO_PIN_9);      // RES# pin, (port, pin)

    DisplayConnectionSTM32Cube connection(&hspi1, &cs, &dc, &res, nullptr);  // Create display connection, (hspi, cs, dc, res, busy=none)
    SSD1331Full<DisplayConnectionSTM32Cube> oled(connection, frameBuffer);  // Create SSD1331 driver, (connection, buffer)
    typedef SSD1331Full<DisplayConnectionSTM32Cube> Chip;

    if (!oled.valid()) {
        printf("SSD1331 init failed (check wiring)\r\n");
        while (true) HAL_Delay(1000);
    }

    // --- Draw the test card into the framebuffer ---
    // Eight 12-pixel colour bars over a 32-row gray ramp show that all channels and the
    // 5-6-5 depth work; the one-pixel frame shows that the whole 96 x 64 area is addressed.
    static const uint16_t bars[8] = {
        Chip::WHITE, Chip::YELLOW, Chip::CYAN, Chip::GREEN, Chip::MAGENTA, Chip::RED, Chip::BLUE, Chip::BLACK
    };
    oled.clear(Chip::BLACK);                        // Fill framebuffer, (color=BLACK)
    for (int16_t x = 0; x < 96; x++) {
        oled.draw_rect(x, 2, 1, 30, bars[x / 12], true);  // Software rectangle, (x, y, w, h, color, fill=false)
        uint8_t g = static_cast<uint8_t>(x * 255 / 95);
        oled.draw_rect(x, 34, 1, 26, Chip::rgb(g, g, g), true);  // Software rectangle, (x, y, w, h, color, fill=false)
    }
    oled.draw_rect(0, 0, 96, 64, Chip::WHITE);      // Software rectangle, (x, y, w, h, color, fill=false)

    // --- Send it once ---
    oled.show();                                    // Send framebuffer, () → None
    HAL_Delay(3000);

    // --- Animate with hardware drawing ---
    // The chip draws the marker straight into its RAM: no framebuffer transfer per step.
    for (int step = 0; step < 60; step++) {
        int x = 4 + (step * 3) % 80;
        oled.show();                                // Send framebuffer, () → None
        oled.hw_rect(static_cast<uint8_t>(x), 36, 10, 10, Chip::WHITE, Chip::RED, true);  // Hardware rectangle, (x, y, w, h, line_color, fill_color=0, fill=false)
        HAL_Delay(50);
    }

    // --- Scroll the test card ---
    // The chip scrolls its own RAM, one column every 6 frames.
    oled.show();                                    // Send framebuffer, () → None
    oled.scroll(1, 0, 64, 0, 0);                    // Scroll, (horizontal, start_row, rows, vertical, interval 0..3)
    printf("done\r\n");

    while (true) {
        HAL_Delay(1000);
    }
}
