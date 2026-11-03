// SSD1331 complete example: Waveshare 0.95inch RGB OLED on a NUCLEO-F411RE.
//
// Complete: every Full method — software drawing, partial update, hardware line/rectangle/
// copy/dim/clear, scrolling, contrast, master current, flip, display modes, power save.
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

    oled.clear(Chip::BLACK);                        // Fill framebuffer, (color=BLACK)
                                                    // sets every pixel; the display is unchanged until show()
    oled.draw_rect(0, 0, 96, 64, Chip::WHITE);      // Software rectangle, (x, y, w, h, color, fill=false)
                                                    // outline into the framebuffer
    oled.draw_rect(4, 4, 30, 20, Chip::RED, true);  // Software rectangle, (x, y, w, h, color, fill=false)
                                                    // filled block
    oled.draw_line(0, 0, 95, 63, Chip::GREEN);      // Software line, (x0, y0, x1, y1, color)
                                                    // Bresenham diagonal
    oled.draw_line(0, 63, 95, 0, Chip::CYAN);       // Software line, (x0, y0, x1, y1, color)
    uint16_t sprite[8 * 8];
    for (int i = 0; i < 64; i++) sprite[i] = (((i / 8) + (i % 8)) & 1) ? Chip::YELLOW : Chip::MAGENTA;
    oled.blit(60, 8, sprite, 8, 8);                 // Copy bitmap, (x, y, bitmap, w, h)
                                                    // RGB565 checkerboard into the framebuffer
    oled.set_pixel(48, 32, Chip::WHITE);            // Set pixel, (x, y, color=WHITE)
    uint16_t c = oled.get_pixel(48, 32);            // Read framebuffer pixel, (x, y) → int RGB565
    printf("pixel 0x%04X\r\n", c);
    oled.show();                                    // Send framebuffer, () → None
                                                    // window 0..95 x 0..63 then 12288 bytes
    HAL_Delay(2000);

    oled.clear(Chip::BLUE);                         // Fill framebuffer, (color=BLACK)
    oled.draw_rect(20, 16, 56, 32, Chip::WHITE, true);  // Software rectangle, (x, y, w, h, color, fill=false)
    oled.show_region(16, 12, 64, 40);               // Send region, (x, y, w, h)
                                                    // only the rows of the window are transferred
    HAL_Delay(2000);

    oled.hw_clear(0, 0, 95, 63);                    // Clear window in display RAM, (x0, y0, x1, y1)
                                                    // chip clears its own RAM, framebuffer untouched
    oled.hw_rect(10, 10, 76, 44, Chip::YELLOW, Chip::RED, true);  // Hardware rectangle, (x, y, w, h, line_color, fill_color=0, fill=false)
                                                    // outline and fill drawn by the chip
    oled.hw_line(0, 0, 95, 63, Chip::WHITE);        // Hardware line, (x0, y0, x1, y1, color)
    HAL_Delay(2000);
    oled.hw_copy(10, 10, 40, 40, 50, 20);           // Hardware copy, (x0, y0, x1, y1, x, y)
    HAL_Delay(2000);
    oled.hw_dim(0, 0, 47, 63);                      // Hardware dim window, (x0, y0, x1, y1)
    HAL_Delay(2000);

    oled.set_display_mode(Chip::MODE_INVERSE);      // Display mode, (mode)
    HAL_Delay(1500);
    oled.set_display_mode(Chip::MODE_ALL_ON);       // Display mode, (mode)
    HAL_Delay(1000);
    oled.set_display_mode(Chip::MODE_ALL_OFF);      // Display mode, (mode)
    HAL_Delay(1000);
    oled.set_display_mode(Chip::MODE_NORMAL);       // Display mode, (mode)

    oled.set_contrast(0x30, 0x30, 0x30);            // Channel contrast, (a, b, c) 0..255
    HAL_Delay(1000);
    oled.set_contrast(0xFF, 0xFF, 0xFF);            // Channel contrast, (a, b, c) 0..255
    oled.set_master_current(4);                     // Master current, (n 1..16) n/16
    HAL_Delay(1000);
    oled.set_master_current(7);                     // Master current, (n 1..16) n/16

    oled.set_flip(false, true);                     // Mirror, (horizontal, vertical)
    oled.show();                                    // Send framebuffer, () → None
    HAL_Delay(1500);
    oled.set_flip(true, true);                      // Mirror, (horizontal, vertical)
    oled.show();                                    // Send framebuffer, () → None
    HAL_Delay(1500);

    oled.scroll(1, 0, 64, 0, 0);                    // Scroll, (horizontal, start_row, rows, vertical, interval 0..3)
                                                    // continuous scroll one column per 6 frames
    HAL_Delay(4000);
    oled.scroll_stop();                             // Stop scrolling, ()

    oled.dim_mode(0x10, 0x10, 0x10, 0x10);          // Dim mode, (a, b, c, precharge 0..31)
    HAL_Delay(1500);
    oled.set_power_save(true);                      // Power save, (on)
    oled.display_off();                             // Display off, ()
    printf("done\r\n");

    while (true) {
        HAL_Delay(1000);
    }
}
