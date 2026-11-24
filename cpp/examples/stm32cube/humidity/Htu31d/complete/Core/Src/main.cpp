#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Htu31d.h"

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
    HTU31DFull sensor(connection);                                  // Create HTU31D driver, (connection) → void
    sensor.init();                                                  // Soft reset, () → void
                                                                    // 0x1E, waits 15 ms; highest resolution, heater off
    float t = 0, rh = 0;
    uint16_t t_raw = 0, rh_raw = 0;
    uint8_t osr_rh = 0, osr_t = 0;
    bool ok = sensor.read(t, rh);                                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
                                                                    // OSR 3 / 3 by default, both CRCs verified
    printf("temperature %.2f C humidity %.1f %%RH\r\n", (double)(t), (double)(rh));
    printf("humidity %.1f %%RH\r\n", (double)(sensor.read_humidity()));// Read humidity only, () → float %RH
                                                                    // command 0x10; NAN on failure
    printf("temperature %.2f C\r\n", (double)(sensor.read_temperature()));// Read temperature only, () → float °C
                                                                    // NAN on failure
    ok = sensor.read_raw(t_raw, rh_raw);                            // Read raw codes, (t_raw&, rh_raw&) → bool
                                                                    // 16-bit codes
    printf("raw T %lu raw RH %lu\r\n", (unsigned long)(t_raw), (unsigned long)(rh_raw));
    ok = sensor.set_resolution(1, 2);                               // Set oversampling, (osr_rh, osr_t) → bool
                                                                    // 0–3 each; higher OSR = finer resolution, longer conversion
    sensor.get_resolution(osr_rh, osr_t);                           // Get oversampling, (osr_rh&, osr_t&) → void
    printf("osr rh %lu osr t %lu\r\n", (unsigned long)(osr_rh), (unsigned long)(osr_t));
    printf("conversion %.1f ms\r\n", (double)(sensor.conversion_time() * 1000.0f));// Typical conversion time, () → float s
    sensor.set_resolution(3, 3);
    sensor.start_conversion();                                      // Start a conversion, () → void
                                                                    // non-blocking; wait conversion_time() before reading
    HAL_Delay(30);
    ok = sensor.read_result(t, rh);                                 // Read last conversion result, (temperature_c&, humidity_pct&) → bool
    printf("result %.2f C %.1f %%RH\r\n", (double)(t), (double)(rh));
    sensor.set_heater(true);                                        // Switch the heater on, (on) → void
                                                                    // 0x04; readings drift while the heater runs
    printf("heater on %lu\r\n", (unsigned long)(sensor.is_heater_on()));// Heater state, () → bool
    sensor.set_heater(false);                                       // Heater off
    printf("diagnostic 0x%02X\r\n", (unsigned)(sensor.diagnostic()));// Read the diagnostic byte, () → uint8_t
                                                                    // test against HTU31DFull::DIAG_* masks
    uint32_t serial = 0;
    ok = sensor.serial_number(serial);                              // Read the serial number, (serial&) → bool
                                                                    // 24-bit, CRC verified
    printf("serial 0x%02X\r\n", (unsigned)(serial));
    printf("dew point %.2f C\r\n", (double)(sensor.dew_point()));   // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula; NAN on failure
    sensor.software_reset();                                        // Soft reset, () → void
                                                                    // 0x1E, waits 15 ms; heater off, OSR 3 / 3
    printf("ok %lu\r\n", (unsigned long)(ok));
    while (1) {}
}
