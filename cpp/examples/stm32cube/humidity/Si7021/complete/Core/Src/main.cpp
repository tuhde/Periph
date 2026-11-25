#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Si7021.h"

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
    Si7021Full sensor(connection);                                      // Create Si7021 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw = 0;
    uint8_t rh_bits = 0, t_bits = 0;

    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // RH without hold master, temperature of the same conversion
    printf("%.2f C, %.1f %%RH\r\n", t, rh);
    printf("humidity %.1f %%RH\r\n", sensor.read_humidity());
    // sensor.read_humidity() → float %RH (NAN on failure)
    printf("temperature %.2f C\r\n", sensor.read_temperature());
    // sensor.read_temperature() → float °C, new conversion (NAN on failure)
    printf("temperature of last RH measurement %.2f C\r\n", sensor.read_temperature_previous());
    // sensor.read_temperature_previous() → float °C, command 0xE0, no new conversion (NAN on failure)
    sensor.read_raw_humidity(raw);                                      // Read raw RH code, (code) → bool
                                                                        // 16-bit code, status bits included
    printf("raw RH %u\r\n", (unsigned)raw);
    sensor.read_raw_temperature(raw);                                   // Read raw temperature code, (code) → bool
    printf("raw T %u\r\n", (unsigned)raw);

    sensor.set_resolution(11, 11);                                      // Set resolution, (rh_bits, temperature_bits) → bool
                                                                        // (12, 14) / (8, 12) / (10, 13) / (11, 11)
    sensor.get_resolution(rh_bits, t_bits);                             // Get resolution, (rh_bits, t_bits) → void
    printf("resolution %u / %u bit\r\n", (unsigned)rh_bits, (unsigned)t_bits);
    printf("conversion time %.1f ms\r\n", sensor.conversion_time() * 1000.0f);
    // sensor.conversion_time() → float s, typical RH + temperature time
    sensor.set_resolution(12, 14);

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // about 3.1 mA; readings drift while on
    printf("heater on %u\r\n", (unsigned)sensor.is_heater_on());
    // sensor.is_heater_on() → bool
    sensor.set_heater(false);
    printf("VDD low %u\r\n", (unsigned)sensor.vdd_low());
    // sensor.vdd_low() → bool, true when VDD is below about 1.9 V
    uint8_t reg = sensor.read_user_register();                          // Read USER_REG_1, () → uint8_t
                                                                        // 0x3A after reset
    sensor.write_user_register(reg);                                    // Write USER_REG_1, (value) → void
                                                                        // reserved bits are preserved
    sensor.set_clock_stretching(false);                                 // Use hold-master commands, (on) → void
                                                                        // needs bus-layer support; off polls the NACKed read header

    uint64_t sn = sensor.serial_number();                               // Electronic serial number, () → uint64_t
    printf("serial number 0x%08lX%08lX\r\n", (unsigned long)(sn >> 32), (unsigned long)(sn & 0xFFFFFFFFUL));
    printf("device type 0x%02X\r\n", (unsigned)sensor.device_type());
    // sensor.device_type() → uint8_t, 0x15 = Si7021
    printf("firmware 0x%02X\r\n", (unsigned)sensor.firmware_revision());
    // sensor.firmware_revision() → uint8_t, 0xFF = firmware 1.0
    printf("dew point %.2f C\r\n", sensor.dew_point());
    // sensor.dew_point() → float °C, one measurement, Magnus formula (NAN on failure)
    sensor.software_reset();                                            // Software reset, () → void
                                                                        // 0xFE, waits 15 ms
    printf("done\r\n");
    while (true) HAL_Delay(1000);
}
