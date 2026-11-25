// UC1611s complete example (4-wire SPI): EA DOGXL240-7, STM32Cube HAL (NUCLEO-F411RE, SPI1)
// Wiring: SCK=PA5 (D13), SI=PA7 (D11), CS0=PB6, CD=PC7, RST=PA9; VDD = 3V3
// Module: EA DOGXL240-7 (240 x 128, 3.3 V only), 4-wire SPI: BM0 = D13 = VSS.
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD.
// Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, partial display, RAM screens, sleep, raw access, reset (SPI has no read-back).
#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "DisplayConnectionSTM32Cube.h"
#include "OutputPinSTM32Cube.h"
#include "UC1611s.h"

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

// SPI1 write-only on the Arduino header: PA5=SCK (D13), PA7=MOSI (D11). Mode 0, MSB first.
// SPI1 is on APB2 (100 MHz): /16 gives 6.25 MHz; the module allows up to 8 MHz.
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
    hspi1.Init.BaudRatePrescaler = SPI_BAUDRATEPRESCALER_16;
    hspi1.Init.FirstBit          = SPI_FIRSTBIT_MSB;
    hspi1.Init.TIMode            = SPI_TIMODE_DISABLE;
    hspi1.Init.CRCCalculation    = SPI_CRCCALCULATION_DISABLE;
    HAL_SPI_Init(&hspi1);
}

#define PRINT(...) printf(__VA_ARGS__)
#define DELAY_MS(ms) HAL_Delay(ms)

template <typename LCD>
static void run(LCD& lcd) {
    // Framebuffer drawing: everything is composed in RAM and sent with show().
    lcd.clear();                                    // Clear framebuffer, ()
                                                    // zeroes every pixel of the framebuffer; the panel is unchanged until show()
    lcd.fill(true);                                 // Fill framebuffer, (on=true)
                                                    // sets (or clears) every pixel
    lcd.fill(false);                                // Fill framebuffer, (on=true)
    lcd.draw_rect(0, 0, 240, 128);                  // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                    // one-pixel outline around the whole panel
    lcd.draw_line(0, 0, 239, 127);                  // Draw line, (x0, y0, x1, y1, on=true)
                                                    // Bresenham line into the framebuffer
    lcd.draw_line(0, 127, 239, 0);                  // Draw line, (x0, y0, x1, y1, on=true)
    lcd.draw_rect(20, 20, 40, 30, true, true);      // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                    // filled rectangle
    lcd.draw_rect(30, 28, 20, 14, false, true);     // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                    // on=false clears pixels: a hole in the filled rectangle
    lcd.set_pixel(120, 64, true);                   // Set pixel, (x, y, on=true)
    bool lit = lcd.get_pixel(120, 64);              // Read framebuffer pixel, (x, y) → bool
                                                    // reads the framebuffer, not the panel
    static const uint8_t icon[16] = {0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00};
    lcd.blit(180, 16, icon, sizeof(icon), 2);       // Copy bitmap, (x, y multiple of 8, columns, len, height_pages=1)
                                                    // two page rows of 8 columns, bit 0 = top pixel
    lcd.show();                                     // Send framebuffer to the display, ()
                                                    // per page: page + column address, then 240 data bytes in one transfer
    DELAY_MS(2000);
    lcd.show_page(2);                               // Send one framebuffer page, (page 0..15)
                                                    // cheaper partial update of 8 rows
    
    // Contrast and display modes.
    lcd.set_contrast(0x70);                         // Set contrast, (value 0..255)
                                                    // writes the potentiometer PM; lower = lighter
    DELAY_MS(800);
    lcd.set_contrast(0x8F);                         // Set contrast, (value 0..255)
                                                    // 0x8F is the DOG example value
    lcd.set_invert(true);                           // Invert display, (on)
                                                    // bit-wise inversion of what is displayed; RAM unchanged
    DELAY_MS(800);
    lcd.set_invert(false);                          // Invert display, (on)
    lcd.set_all_on(true);                           // Light all pixels, (on)
                                                    // test mode, RAM unchanged
    DELAY_MS(800);
    lcd.set_all_on(false);                          // Light all pixels, (on)
    lcd.set_flip(false, true);                      // Mirror display, (mirror_x, mirror_y)
                                                    // MY = 1: rows mirrored at once; MX is applied to RAM writes
    DELAY_MS(800);
    lcd.set_flip(true, false);                      // Mirror display, (mirror_x, mirror_y)
                                                    // back to the bottom-view default
    lcd.show();                                     // Send framebuffer to the display, ()
    
    // Hardware scroll with a fixed header.
    lcd.set_fixed_lines(2);                         // Set fixed lines, (lines 0..15)
                                                    // the top 2 x 2 = 4 rows stay put
    for (uint8_t line = 0; line <= 32; line += 4) {
        lcd.set_scroll_line(line);                  // Scroll the image up, (line 0..159 - 2 * fixed)
                                                    // rows enter from the bottom as the image moves up
        DELAY_MS(120);
    }
    lcd.set_scroll_line(0);                         // Scroll the image up, (line 0..159 - 2 * fixed)
    lcd.set_fixed_lines(0);                         // Set fixed lines, (lines 0..15)
    
    // LCD drive tuning (the DOG defaults are already sent at init).
    lcd.set_bias_ratio(2);                          // Set bias ratio, (ratio 0..3)
                                                    // 0 = 1/5, 1 = 1/10, 2 = 1/11 (reset), 3 = 1/12
    lcd.set_temperature_compensation(1);            // Set temperature compensation, (curve 0..3)
                                                    // 1 = -0.10 %/degC, the DOG example value
    lcd.set_line_rate(3);                           // Set line rate, (rate 0..3)
                                                    // 3 = 9.4 kilo-lines per second in On/Off mode
    lcd.set_pump(true);                             // Select VLCD source, (internal=true)
                                                    // internal 11x charge pump
    lcd.set_panel_loading(true);                    // Set panel loading, (high=true)
                                                    // 33..55 nF panel capacitance
    lcd.set_nline_inversion(0, false);              // Set N-line inversion, (lines 0..63, xor=false)
                                                    // 0 = off
    lcd.set_isolation_clock(1, 0);                  // Set isolation clock, (front 0..15, back 0..15)
                                                    // the reset values
    
    // Partial display: scan only the middle 64 rows.
    lcd.set_partial_display(true, 32, 95);          // Set partial display, (on, start=0, end=height-1)
                                                    // rows outside 32..95 are not scanned and save power
    DELAY_MS(1500);
    lcd.set_partial_display(false);                 // Set partial display, (on, start=0, end=height-1)
                                                    // back to the full panel
    
    // Four RAM screens: draw into screen 1, then switch to it.
    lcd.set_write_pattern(1);                       // Select RAM screen to write, (pattern 0..3)
                                                    // show() now writes RAM screen 1
    lcd.fill(false);                                // Fill framebuffer, (on=true)
    lcd.draw_rect(40, 30, 160, 68, true, true);     // Draw rectangle, (x, y, w, h, on=true, fill=false)
    lcd.show();                                     // Send framebuffer to the display, ()
                                                    // screen 0 is still displayed
    lcd.set_display_pattern(1);                     // Select displayed RAM screen, (pattern 0..3)
                                                    // the display now shows RAM screen 1
    DELAY_MS(1500);
    lcd.set_display_pattern(0);                     // Select displayed RAM screen, (pattern 0..3)
    lcd.set_write_pattern(0);                       // Select RAM screen to write, (pattern 0..3)
    
    // Sleep, raw access, reset.
    lcd.sleep();                                    // Enter sleep, ()
                                                    // drivers, pump and clock stop; registers and RAM are kept
    DELAY_MS(1000);
    lcd.wake();                                     // Leave sleep, ()
                                                    // the chip restores VLCD itself
    static const uint8_t nop[1] = {0xE3};
    lcd.send_command(nop, sizeof(nop));             // Send raw command bytes, (bytes, len)
                                                    // 0xE3 = NOP
    static const uint8_t zeros[8] = {0};
    lcd.set_display_pattern(0);                     // Select displayed RAM screen, (pattern 0..3)
    lcd.send_data(zeros, sizeof(zeros));            // Send raw RAM data, (bytes, len)
                                                    // written at the current RAM address
    lcd.reset();                                    // System Reset and reconfigure, ()
                                                    // registers back to the DOG settings with the cached contrast and mapping; RAM is kept
    (void)lit;
    PRINT("done\n");
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init();

    OutputPinSTM32Cube cs(GPIOB, GPIO_PIN_6);       // CS0 pin, (port, pin)
    OutputPinSTM32Cube dc(GPIOC, GPIO_PIN_7);       // CD (D/C#) pin, (port, pin)
    OutputPinSTM32Cube res(GPIOA, GPIO_PIN_9);      // RST pin, (port, pin)

    DisplayConnectionSTM32Cube connection(&hspi1, &cs, &dc, &res, nullptr);  // Create display connection, (hspi, cs, dc, res, busy=none)
    static UC1611sSpiFull<DisplayConnectionSTM32Cube> lcd(connection);  // Create UC1611s driver, (connection, width=240, height=128, topView=false, contrast=0x8F)
    if (!lcd.valid()) {
        printf("UC1611s init failed\r\n");
        while (true) HAL_Delay(1000);
    }
    run(lcd);
    while (true) {
        HAL_Delay(1000);
    }
}
