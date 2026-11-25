// SSD1305 complete example: SSD1305 128 x 64 OLED, STM32Cube HAL (NUCLEO-F411RE, I2C1)
// Wiring: SCL=PB8 (D15), SDA=PB9 (D14), A2=PB6, CD=PC7 (low), RST=PA9; A3 tied high -> 0x3C/0x3D; VDD = 3V3
// Module: 1.3" 128 x 64 OLED (SSD1305, RAM columns 4..131), I2C address 0x3C (SA0 = low) or 0x3D (SA0 = high); pull-ups 4.7 k to 3.3 V.
// Complete: every Full method - drawing, contrast, invert, flip, scroll, drive tuning, reads, sleep.
#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "SSD1305.h"

static UART_HandleTypeDef huart2;
static I2C_HandleTypeDef hi2c1;

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

static void i2c1_init(void) {
    __HAL_RCC_I2C1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_8 | GPIO_PIN_9;  // PB8=SCL, PB9=SDA
    gpioInit.Mode      = GPIO_MODE_AF_OD;
    gpioInit.Pull      = GPIO_PULLUP;               // internal pull-up, in case the breakout has none
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF4_I2C1;
    HAL_GPIO_Init(GPIOB, &gpioInit);

    hi2c1.Instance             = I2C1;
    hi2c1.Init.ClockSpeed      = 400000;  // external pull-ups on SDA/SCL: 400 kHz
    hi2c1.Init.DutyCycle       = I2C_DUTYCYCLE_2;
    hi2c1.Init.OwnAddress1     = 0;
    hi2c1.Init.AddressingMode  = I2C_ADDRESSINGMODE_7BIT;
    hi2c1.Init.DualAddressMode = I2C_DUALADDRESS_DISABLE;
    hi2c1.Init.OwnAddress2     = 0;
    hi2c1.Init.GeneralCallMode = I2C_GENERALCALL_DISABLE;
    hi2c1.Init.NoStretchMode   = I2C_NOSTRETCH_DISABLE;
    HAL_I2C_Init(&hi2c1);
}

#define PRINT(...) printf(__VA_ARGS__)
#define DELAY_MS(ms) HAL_Delay(ms)

static void run(SSD1305Full& lcd) {
    // Framebuffer drawing: everything is composed in RAM and sent with show().
    lcd.clear();                                    // Clear framebuffer, ()
                                                    // zeroes every pixel of the framebuffer; the panel is unchanged until show()
    lcd.fill(true);                                 // Fill framebuffer, (on=true)
                                                    // sets (or clears) every pixel
    lcd.fill(false);                                // Fill framebuffer, (on=true)
    lcd.draw_rect(0, 0, 128, 64);                   // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                    // one-pixel outline around the whole panel
    lcd.draw_line(0, 0, 127, 63);                   // Draw line, (x0, y0, x1, y1, on=true)
                                                    // Bresenham line into the framebuffer
    lcd.draw_line(0, 63, 127, 0);                   // Draw line, (x0, y0, x1, y1, on=true)
    lcd.draw_rect(10, 10, 30, 20, true, true);      // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                    // filled rectangle
    lcd.draw_rect(18, 16, 14, 8, false, true);      // Draw rectangle, (x, y, w, h, on=true, fill=false)
                                                    // on=false clears pixels: a hole in the filled rectangle
    lcd.set_pixel(64, 32, true);                    // Set pixel, (x, y, on=true)
    bool lit = lcd.get_pixel(64, 32);               // Read framebuffer pixel, (x, y) → bool
                                                    // reads the framebuffer, not the panel
    (void)lit;
    static const uint8_t icon[16] = {0x3C, 0x42, 0x81, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C, 0x00, 0x7E, 0x81, 0x81, 0x81, 0x7E, 0x00};
    lcd.blit(100, 16, icon, sizeof(icon), 2);       // Copy bitmap, (x, y multiple of 8, columns, len, height_pages=1)
                                                    // two page rows of 8 columns, bit 0 = top pixel
    lcd.show();                                     // Send framebuffer to the display, ()
                                                    // per page: page + column command, then 128 data bytes in 31-byte chunks
    DELAY_MS(2000);
    lcd.show_page(2);                               // Send one framebuffer page, (page 0..7)
                                                    // cheaper partial update of 8 rows

    // Contrast and display modes.
    lcd.set_contrast(0x20);                         // Set contrast, (value 0..255)
                                                    // segment drive current; reset value is 0x80
    DELAY_MS(800);
    lcd.set_contrast(0x80);                         // Set contrast, (value 0..255)
    lcd.set_invert(true);                           // Invert display, (on)
                                                    // RAM 0 = pixel on; RAM is unchanged
    DELAY_MS(800);
    lcd.set_invert(false);                          // Invert display, (on)
    lcd.set_all_on(true);                           // Force all pixels on, (on)
                                                    // test mode, RAM is kept
    DELAY_MS(800);
    lcd.set_all_on(false);                          // Force all pixels on, (on)
    lcd.set_flip(true, true);                       // Mirror display, (horizontal, vertical)
                                                    // segment re-map and COM scan direction; framebuffer unchanged
    DELAY_MS(1500);
    lcd.set_flip(false, false);                     // Mirror display, (horizontal, vertical)

    // Hardware vertical scroll and drive tuning.
    for (uint8_t line = 0; line < 64; line += 2) {
        lcd.set_start_line(line);                   // Scroll the image up, (line 0..63)
                                                    // RAM line shown at COM0; the image wraps around
        DELAY_MS(30);
    }
    lcd.set_start_line(0);                          // Scroll the image up, (line 0..63)
    lcd.set_display_offset(0);                      // Set COM offset, (offset 0..63)
                                                    // shifts the COM mapping
    lcd.set_multiplex(64);                          // Set multiplex ratio, (ratio 16..64)
                                                    // number of driven rows
    lcd.set_clock(1, 7);                            // Set clock, (divide 1..16, osc 0..15)
                                                    // divide ratio 1 at the reset oscillator setting
    lcd.set_precharge(2, 2);                        // Set pre-charge, (phase1 1..15, phase2 1..15)
                                                    // pre-charge and discharge periods in display clocks
    lcd.set_com_config(true);                       // Set COM pin configuration, (alternative, remap=false)
                                                    // alternative is the reset value and right for 128 x 64 modules
    lcd.set_vcomh(0x34);                            // Set VCOMH deselect level, (value 0x00..0x3C)
                                                    // 0x34 (about 0.77 x VCC) is the reset value
    lcd.set_dim_mode(0x40, 0x40);                   // Configure dim levels, (contrast 0..255, brightness 0..255)
                                                    // used while dim mode is on
    lcd.set_dim(true);                              // Dim mode, (on)
                                                    // 0xAC display on in dim mode
    DELAY_MS(1000);
    lcd.set_dim(false);                             // Dim mode, (on)
    lcd.set_brightness(0x80);                       // Set colour-bank brightness, (value 0..255)
                                                    // no visible effect in monochrome mode
    lcd.set_area_color(false);                      // Set area-colour mode, (enable, low_power=false)
                                                    // keep disabled on monochrome panels
    lcd.set_lut(49, 63, 63, 63);                    // Set pulse widths, (bank0, a, b, c each 31..63)
                                                    // reset values
    static const uint8_t banks[4] = {0, 0, 0, 0};
    lcd.set_bank_colors(banks, banks);              // Assign bank colours, (banks_1_16, banks_17_32=nullptr)
                                                    // all banks colour A
    lcd.set_addressing_mode(SSD1305Minimal::ADDR_HORIZONTAL);// Select addressing mode, (mode ADDR_PAGE|ADDR_HORIZONTAL|ADDR_VERTICAL)
                                                    // show() then streams the frame in one transfer
    lcd.show();                                     // Update display, ()
    lcd.set_addressing_mode(SSD1305Minimal::ADDR_PAGE);// Select addressing mode, (mode ADDR_PAGE|ADDR_HORIZONTAL|ADDR_VERTICAL)

    // Hardware scrolling.
    lcd.set_scroll_horizontal(true, 0, 7, 0, 1);    // Set horizontal scroll, (right, start_page, end_page, interval 0..6, columns=1)
                                                    // interval index 0 = 6 frames per step
    lcd.scroll_start();                             // Start scrolling, ()
    DELAY_MS(1000);
    lcd.scroll_stop();                              // Stop scrolling, ()
                                                    // deactivates and rewrites the RAM from the framebuffer
    lcd.set_scroll_area(0, 64);                     // Set vertical scroll area, (top_fixed 0..63, scroll_rows 1..64)
    lcd.set_scroll_vertical(true, 0, 7, 0, 1, 1);   // Set vertical+horizontal scroll, (right, start_page, end_page, interval, columns, rows 1..63)
    lcd.scroll_start();                             // Start scrolling, ()
    DELAY_MS(1000);
    lcd.scroll_stop();                              // Stop scrolling, ()

    // Reads (I2C only) and raw commands.
    uint8_t status = 0;
    bool ok = lcd.read_status(status);              // Read status, (status&) → bool
                                                    // bit 6 = 1 when the display is off
    uint8_t ram[8];
    ok = lcd.read_page(0, 2, ram, 8);               // Read display RAM, (page 0..7, column, buf, n) → bool
                                                    // sets the address and discards the dummy byte
    (void)ok;
    static const uint8_t nop[1] = {0xE3};
    lcd.send_command(nop, 1);                       // Send raw command stream, (bytes, len)
                                                    // NOP

    // Power.
    lcd.set_display(false);                         // Display on/off, (on)
                                                    // RAM and settings are kept
    DELAY_MS(1000);
    lcd.set_display(true);                          // Display on/off, (on)
    lcd.sleep();                                    // Enter sleep, ()
                                                    // display off; switch VCC off afterwards
    DELAY_MS(1000);
    lcd.wake();                                     // Leave sleep, ()
                                                    // display on and a 100 ms wait (tAF)
    PRINT("status 0x%02X\n", status);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube conn(&hi2c1, 0x3C);             // Create I2C connection, (hi2c, addr=0x3C)
    static SSD1305Full lcd(conn);  // Create SSD1305 driver, (connection, width=128, height=64, column_offset=4)
    if (!lcd.valid()) {
        printf("SSD1305 init failed\r\n");
        while (true) HAL_Delay(1000);
    }
    run(lcd);
    while (true) {
        HAL_Delay(1000);
    }
}
