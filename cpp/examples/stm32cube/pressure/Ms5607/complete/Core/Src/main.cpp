#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Ms5607.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x77);
    MS5607Full sensor(connection);                                  // Create MS5607 driver, (connection) → void
    sensor.init();                                                  // Reset, read PROM, verify CRC-4, () → bool
    float t = 0, p = 0;
    uint32_t d1 = 0, d2 = 0, adc = 0;
    uint16_t prom[8], k[6];
    bool ok = sensor.read(t, p);                                    // Read temperature and pressure, (temperature_c&, pressure_pa&) → bool
                                                                    // OSR 4096, second-order compensation applied
    printf("temperature %.2f C pressure %.1f Pa\r\n", (double)(t), (double)(p));
    printf("pressure %.1f Pa\r\n", (double)(sensor.read_pressure())); // Read pressure only, () → float Pa
                                                                    // NAN on failure
    printf("temperature %.2f C\r\n", (double)(sensor.read_temperature())); // Read temperature only, () → float °C
                                                                    // converts D2 only
    ok = sensor.read_raw(d1, d2);                                   // Read raw ADC codes, (d1&, d2&) → bool
                                                                    // 24-bit pressure and temperature codes
    printf("raw D1 %lu raw D2 %lu\r\n", (unsigned long)(d1), (unsigned long)(d2));
    sensor.compensate(d1, d2, t, p);                                // Compensate raw codes, (d1, d2, temperature_c&, pressure_pa&, second_order=true) → void
                                                                    // pure computation, no bus access
    sensor.compensate(d1, d2, t, p, false);
    sensor.set_oversampling(1024);                                  // Select oversampling ratio, (osr) → bool
                                                                    // 256, 512, 1024, 2048 or 4096
    printf("osr %lu\r\n", (unsigned long)(sensor.get_oversampling())); // Current oversampling ratio, () → uint16
    printf("conversion time %.3f ms\r\n", (double)(sensor.conversion_time() * 1000.0f)); // Max conversion time, (osr=0) → float s
    sensor.start_conversion(MS5607Full::TEMPERATURE);               // Start a conversion, (kind, osr=0) → bool
                                                                    // PRESSURE = D1, TEMPERATURE = D2; non-blocking
    HAL_Delay(12);
    ok = sensor.read_adc(adc);                                      // Read the ADC result, (value&) → bool
                                                                    // only after the conversion time, 24-bit
    printf("adc %lu\r\n", (unsigned long)(adc));
    sensor.set_oversampling(4096);
    ok = sensor.read_prom(prom);                                    // Read the PROM words, (words[8]) → bool
    sensor.coefficients(k);                                         // Calibration coefficients, (c[6]) → void
                                                                    // C1 ... C6
    printf("crc ok %lu\r\n", (unsigned long)(sensor.prom_crc_ok())); // Verify the PROM CRC-4, () → bool
    printf("serial %lu\r\n", (unsigned long)(sensor.serial_code())); // Serial code, () → uint16
                                                                    // PROM word 7 bits 15:4
    printf("altitude %.2f m\r\n", (double)(sensor.altitude()));     // Altitude from pressure, (sea_level_pa=101325) → float m
                                                                    // international barometric formula
    sensor.reset();                                                 // Reset and reload PROM, () → bool
                                                                    // 0x1E, 3 ms wait
    printf("ok %lu\r\n", (unsigned long)(ok));
    while (1) {}
}
