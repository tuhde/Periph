#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "HT16K33.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x70);
    HT16K33Full matrix(connection);                      // Create HT16K33 driver, (connection)
    HAL_Delay(2000);

    matrix.clear();                                       // Blank the display, () → void
                                                          // zeros the shadow RAM and writes it
    matrix.set_pixel(2, 5);                               // Set one LED, (com 0–7, row 0–15, on) → bool
    matrix.show();                                        // Write the shadow RAM, () → void
    bool lit = matrix.get_pixel(2, 5);                    // Read a pixel from the shadow RAM, (com, row) → bool
    (void)lit;
    static const uint8_t raw[2] = {0xFF, 0x0F};
    matrix.write_raw(raw, 2, 0);                          // Write raw RAM bytes, (data, len 1–16, address 0–15) → bool
                                                          // com 0 rows 0–11 lit
    matrix.set_brightness(8);                             // Set dimming, (level 0–15) → void
                                                          // 9/16 duty
    matrix.set_blink(1.0f);                               // Set blink frequency, (0, 0.5, 1, 2 Hz) → bool
                                                          // blinks at 1 Hz
    HAL_Delay(3000);
    matrix.set_blink(0.0f);
    matrix.display_off();                                 // Display output off, () → void
    matrix.display_on();                                  // Display output on, () → void
    matrix.set_int_output(true, false);                   // ROW15/INT pin as INT output, (enable, active_high) → void
                                                          // active low interrupt output
    uint64_t keys = matrix.read_keys();                   // Read and clear key data, () → uint64_t bitmask
    (void)keys;
    bool pressed = matrix.key_pressed();                  // Read the INT flag, () → bool
    (void)pressed;
    uint64_t polled = matrix.poll_interrupt();            // Read keys if INT flag set, () → uint64_t (0 if none)
    (void)polled;
    matrix.on_interrupt([](uint64_t k) { (void)k; });     // Subscribe to key presses, (callback, intPin) → void
                                                          // polls via service() when no INT pin is wired
    matrix.service();                                     // Poll once and dispatch, () → void
    matrix.off_interrupt();                               // Unsubscribe, () → void
    matrix.standby();                                     // Oscillator off, () → void
    matrix.wake();                                        // Oscillator on and restore state, () → void

    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) {
    HAL_Delay(1000); 
        HAL_Delay(10);
    }

}
