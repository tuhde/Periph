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
                                                                        // 15 ms power-up wait, soft reset, 15 ms
    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // no hold master, CRC checked, one retry on a mismatch
    printf("%.2f C, %.1f %%RH\r\n", t, rh);
    printf("humidity %.1f %%RH\r\n", sensor.read_humidity());
    // sensor.read_humidity() → float %RH (NAN on failure), not clamped
    printf("temperature %.2f C\r\n", sensor.read_temperature());
    // sensor.read_temperature() → float °C (NAN on failure)
    printf("compensated humidity %.1f %%RH\r\n", sensor.read_humidity_compensated());
    // sensor.read_humidity_compensated() → float %RH, -0.15 %RH/°C relative to 25 °C (NAN on failure)
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw codes, (temperature_raw, humidity_raw) → bool
                                                                        // 16-bit codes, status bits cleared
    printf("raw T %u raw RH %u\r\n", (unsigned)raw_t, (unsigned)raw_rh);

    sensor.set_resolution(3);                                           // Set resolution, (res) → bool
                                                                        // 0 = 12/14 bit RH/T, 1 = 8/12, 2 = 10/13, 3 = 11/11
    res = sensor.get_resolution();                                      // Get resolution, () → uint8_t
    printf("resolution setting %u\r\n", (unsigned)res);
    sensor.set_resolution(0);                                           // Back to 12/14 bit

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // about 5.5 mW; readings drift while on
    printf("heater on %u\r\n", (unsigned)(sensor.heater()));
    // sensor.heater() → bool
    sensor.set_heater(false);                                           // Heater off
    printf("battery low %u\r\n", (unsigned)(sensor.battery_low()));
    // sensor.battery_low() → bool, true when VDD is below about 2.25 V
    reg = sensor.read_user_register();                          // Read the user register, () → uint8_t
                                                                        // 0x02 after reset
    printf("user register 0x%02X\r\n", (unsigned)reg);
    sensor.set_hold_master(false);                                      // Use hold-master commands, (on) → void
                                                                        // needs bus-layer clock stretching; off polls the NACKed read header
    printf("dew point %.2f C\r\n", sensor.dew_point());
    // sensor.dew_point() → float °C, one measurement (NAN on failure)
    sensor.soft_reset();                                                // Soft reset, () → void
                                                                        // 0xFE, waits 15 ms; the heater bit survives
    printf("done\r\n");
    while (true) HAL_Delay(1000);
}
