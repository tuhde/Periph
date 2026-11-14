#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "HTU21DF.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x40);
    HTU21DFFull sensor(connection);                                     // Create HTU21D-F driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0;
    uint8_t reg = 0, res = 0;

    sensor.init();                                                      // Soft reset, () → void
    sensor.soft_reset();                                                // Soft reset, () → void
    reg = sensor.read_user_register();                                  // Read the user register, () → uint8_t
    printf("user register 0x%02X\r\n", (unsigned)reg);
    res = sensor.get_resolution();                                      // Get resolution, () → uint8_t
    printf("resolution setting %u\r\n", (unsigned)res);

    // --- Five readings at 12/14 bit ---
    for (uint8_t i = 0; i < 5; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        printf("%.2f C %.1f %%RH (compensated %.1f) dew point %.2f C\r\n", t, rh, sensor.read_humidity_compensated(), sensor.dew_point());
        // sensor.read_humidity_compensated() → float %RH ; sensor.dew_point() → float °C
        HAL_Delay(2000);
    }
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw codes, (temperature_raw, humidity_raw) → bool
    printf("raw T %u raw RH %u\r\n", (unsigned)raw_t, (unsigned)raw_rh);

    // --- 8/12 bit: shorter conversion, coarser resolution ---
    sensor.set_resolution(1);                                           // Set resolution, (res) → bool
    uint32_t t0 = HAL_GetTick();
    sensor.read(t, rh);
    uint32_t dt = HAL_GetTick() - t0;
    printf("8/12 bit: %.2f C %.1f %%RH in %lu ms\r\n", t, rh, (unsigned long)dt);
    sensor.set_resolution(0);                                           // Back to 12/14 bit
    sensor.set_hold_master(false);                                      // Use hold-master commands, (on) → void

    // --- Heater self-check: 10 s ---
    float rh_before = sensor.read_humidity();
    float t_before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    HAL_Delay(10000);
    printf("heater on %u\r\n", (unsigned)(sensor.heater()));
    printf("RH drop %.1f %%RH, temperature rise %.2f K\r\n", rh_before - sensor.read_humidity(), sensor.read_temperature() - t_before);
    sensor.set_heater(false);                                           // Heater off
    printf("battery low %u\r\n", (unsigned)(sensor.battery_low()));
    while (true) HAL_Delay(1000);
}
