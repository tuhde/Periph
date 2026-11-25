#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "PCF8578.h"

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
    hi2c1.Init.ClockSpeed      = 100000;
    hi2c1.Init.DutyCycle       = I2C_DUTYCYCLE_2;
    hi2c1.Init.OwnAddress1     = 0;
    hi2c1.Init.AddressingMode  = I2C_ADDRESSINGMODE_7BIT;
    hi2c1.Init.DualAddressMode = I2C_DUALADDRESS_DISABLE;
    hi2c1.Init.OwnAddress2     = 0;
    hi2c1.Init.GeneralCallMode = I2C_GENERALCALL_DISABLE;
    hi2c1.Init.NoStretchMode   = I2C_NOSTRETCH_DISABLE;
    HAL_I2C_Init(&hi2c1);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x3C);
    PCF8578Full lcd(connection, 16);                          // Create PCF8578 driver, (connection, rows=16 8/16/24/32)
                                                              // clears RAM and switches the display on in mixed mode

    lcd.clear();                                              // Clear display RAM, () → void
                                                              // zeroes all 4 banks x 40 columns and the shadow
    lcd.set_pixel(1, 1);                                      // Set one pixel, (x 0–23, y 0–15, on=true) → void
                                                              // writes a single RAM byte from the shadow
    static const uint8_t frame[] = {0xFF, 0x81, 0xFF};
    lcd.write_columns(4, 0, frame, sizeof(frame));            // Write column bytes, (x 0–23, bank 0–1, data, len) → bool
                                                              // character access mode, bit 0 = top row of the bank
    bool on = lcd.get_pixel(1, 1);                            // Read pixel from shadow, (x, y) → bool
                                                              // no bus traffic
    lcd.fill(true);                                           // Fill display, (on=true) → void
                                                              // every displayed pixel on
    lcd.fill(false);                                          // Fill display, (on=true) → void
                                                              // every displayed pixel off
    lcd.draw_line(0, 0, 23, 15);                              // Draw line, (x0, y0, x1, y1, on=true) → void
                                                              // Bresenham, written as one batch of changed bytes
    lcd.draw_rect(0, 0, 24, 16);                              // Draw rectangle outline, (x, y, w, h, on=true, fill=false) → void
                                                              // border around the whole panel
    lcd.draw_rect(8, 4, 8, 8, true, true);                    // Draw filled rectangle, (x, y, w, h, on=true, fill=false) → void
                                                              // solid 8x8 block in the middle
    static const uint8_t bitmap[] = {0x18, 0x24, 0x42, 0x81};
    lcd.show_bitmap(2, 8, bitmap, sizeof(bitmap), 1);         // Blit bitmap, (x, y multiple of 8, data, width, bands) → bool
                                                              // one 8-pixel-high band at the lower bank
    lcd.set_display(PCF8578Full::DISPLAY_INVERSE);            // Set display status, (status 0 blank/1 normal/2 all on/3 inverse) → bool
                                                              // inverse video; RAM is preserved
    lcd.set_display(PCF8578Full::DISPLAY_NORMAL);             // Set display status, (status 0–3) → bool
                                                              // back to normal video
    lcd.set_mode(24, true);                                   // Set multiplex rate, (rows 8/16/24/32, mixed=true) → bool
                                                              // 1:24 drive; mixed mode keeps RAM accessible
    lcd.set_mode(16, true);                                   // Set multiplex rate, (rows 8/16/24/32, mixed=true) → bool
                                                              // back to 1:16
    lcd.set_start_bank(1);                                    // Set start bank, (bank 0–3) → bool
                                                              // shows RAM bank 1 at the top (hardware scroll)
    lcd.scroll(-1);                                           // Scroll by banks, (banks int) → void
                                                              // adds -1 to the start bank modulo banks in use
    static const uint8_t ram[] = {0xAA, 0x55};
    lcd.write_ram(16, 0, ram, sizeof(ram));                   // Write raw RAM, (x 0–39, bank 0–3, data, len, mode=0) → bool
                                                              // character order at RAM column 16
    uint8_t data[2];
    lcd.read_ram(16, 0, data, sizeof(data));                  // Read raw RAM, (x 0–39, bank 0–3, buf, n, mode=0) → bool
                                                              // address-set commands, repeated start, then a read
    lcd.device_select(0);                                     // Select device subaddress, (subaddress 0–15) → bool
                                                              // 0 addresses this PCF8578; others target cascaded PCF8579s
    lcd.reset();                                              // Re-initialise, () → void
                                                              // clears RAM and restores normal video
    (void)on;
    (void)data;

    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) HAL_Delay(1000);
}
