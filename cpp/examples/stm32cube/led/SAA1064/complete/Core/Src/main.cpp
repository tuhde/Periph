#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "SAA1064.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x38);
    SAA1064Full display(connection);                                // Create SAA1064 driver, (connection)
    const int8_t digits[4] = {1, 2, 3, 4};
    bool ok = display.show_digits(digits);                          // Show four digits, (digits int8_t[4], 0–9 or BLANK) → bool
                                                                    // default mapping a=bit0 … g=bit6, dp=bit7
    HAL_Delay(1000);
    ok = display.show_number(42);                                   // Show a number, (value 0–9999) → bool
                                                                    // leading zeros are blanked
    const uint8_t raw[2] = {0x7F, 0x7F};
    ok = display.write_raw(raw, 2, 3);                              // Write raw segment bytes, (patterns, len 1–4, first_digit 1–4) → bool
                                                                    // digits 3 and 4 show 8
    HAL_Delay(1000);
    ok = display.show_digit(2, 7, true);                            // Show one digit, (position 1–4, value 0–9 or BLANK, dp) → bool
                                                                    // digit 2 = 7 with decimal point
    ok = display.set_decimal_point(2, false);                       // Switch a decimal point, (position 1–4, on) → bool
                                                                    // keeps the cached segments
    const uint8_t map[8] = {0, 1, 2, 3, 4, 5, 6, 7};
    ok = display.set_segment_map(map);                              // Remap segment bits, (map a–g, dp → bit 0–7) → bool
    ok = display.set_current(21);                                   // Set segment current, (ma 0, 3, 6, 9, 12, 15, 18, 21) → bool
                                                                    // C4–C6
    display.set_mode(false);                                        // Select static/dynamic mode, (dynamic) → void
                                                                    // static shows only digits 1 and 2
    HAL_Delay(1000);
    display.set_mode(true);
    display.blank(true, false);                                     // Blank digit pairs, (digits_13, digits_24) → void
                                                                    // digits 1+3 off
    HAL_Delay(1000);
    display.blank(false, false);
    display.segment_test(true);                                     // Segment test, (on) → void
                                                                    // lights every segment
    HAL_Delay(1000);
    display.segment_test(false);
    printf("status 0x%02X\r\n", (unsigned)(display.read_status())); // Read the status byte, () → uint8_t
    printf("power reset %lu\r\n", (unsigned long)(display.power_reset_flag()));// Read the PR flag, () → bool
                                                                    // set after power-up, clears when read
    display.write_control(0x47);                                    // Write the raw control byte, (value 0–127) → void
                                                                    // dynamic mode, unblanked, 12 mA
    display.clear();                                                // Blank all digits, () → void
    printf("ok %lu\r\n", (unsigned long)(ok));

    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) {
    HAL_Delay(1000); 
        HAL_Delay(10);
    }

}
