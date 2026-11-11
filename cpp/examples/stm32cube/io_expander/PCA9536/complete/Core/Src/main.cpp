#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "PCA9536.h"

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

// Callbacks are plain function pointers (no std::function, no heap).
static void onChange(uint8_t changed) {
    printf("changed=0x%X\r\n", changed);
}
static void onPin(PCA9536Full::IOExpanderPin*) {
    printf("P2 falling\r\n");
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x41);

    PCA9536Full chip(connection);                           // Create PCA9536 driver, (connection, addr=0x41)
                                                            // writes the reset values; all pins start as inputs
    chip.configure_polarity(0x00);                              // Normal input polarity, (mask=0x00) → void
                                                            // writes POLARITY; 1 bits invert the INPUT read
    chip.set_direction(0x0C);                                   // P0–P1 outputs, P2–P3 inputs, (mask=0x0C) → void
                                                            // OUTPUT is written before CONFIG (no glitch)
    uint8_t dir = chip.get_direction();                         // Read the direction shadow, () → uint8_t
                                                            // 1 = input, 0 = output
    printf("CONFIG=0x%X\r\n", dir);

    for (uint8_t n = 0; n < 2; n++) {
        auto p = chip.pin(n);                                   // Get LED pin proxy, (n) → IOExpanderPin
                                                            // Full proxy with watch support
        p.mode(OUTPUT);                                         // Set pin direction, (m INPUT/OUTPUT) → bool
                                                            // latches OUTPUT, then clears the CONFIG bit
        p.low();                                                // Drive pin low, () → void
                                                            // clears the OUTPUT bit via the shadow
        p.toggle();                                             // Invert the output latch, () → void
                                                            // one OUTPUT write, no read-modify-write
    }

    chip.write_port(0, 0x01);                                   // Write the output latch, (port=0, mask) → void
                                                            // whole-port OUTPUT write, updates the shadow
    uint8_t port_val = chip.read_port();                        // Read all 4 pins, (port=0) → uint8_t bitmask
                                                            // reads INPUT (actual pin levels)
    printf("PORT=0x%X\r\n", port_val);
    uint8_t config = chip.read_register(0x03);                  // Read raw register, (reg) → uint8_t
                                                            // escape hatch for debugging
    printf("CONFIG=0x%02X\r\n", config);
    chip.write_register(0x02, 0x04);                            // Write raw register, (reg, value) → bool
                                                            // POLARITY: invert P2

    chip.watchChanges(onChange);                                // Register change callback, (callback) → void
                                                            // no INT pin exists; service() polls and dispatches
    chip.pin(2).watch(onPin, InputPin::kFalling);               // Watch P2 falling edges, (handler, trigger) → bool
                                                            // filters the edge in software; all pins share one poll
    chip.service();                                             // Poll once and dispatch callbacks, () → void
                                                            // reads INPUT; call periodically from the main loop
    uint8_t changed = chip.pollChanges();                       // Read INPUT, get changed mask, () → uint8_t
                                                            // XOR with the last INPUT value
    printf("changed=0x%X\r\n", changed);
    chip.pin(2).unwatch();                                      // Stop watching P2, () → void
                                                            // drops the handler
    chip.unwatchChanges();                                      // Remove change callback, () → void
                                                            // stops dispatch
    chip.reset();                                               // Restore reset values, () → void
                                                            // OUTPUT=0xFF, POLARITY=0x00, CONFIG=0xFF
    while (true) HAL_Delay(1000);
}
