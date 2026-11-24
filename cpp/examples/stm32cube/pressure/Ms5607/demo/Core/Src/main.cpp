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

    // --- Calibration data ---
    // Six coefficients and a CRC-4 are factory-programmed into the PROM.
    float t = 0, p = 0;
    uint32_t d1 = 0, d2 = 0, adc = 0;
    uint16_t prom[8], k[6];
    sensor.read_prom(prom);                                         // Read the PROM words, (words[8]) → bool
    sensor.coefficients(k);                                         // Calibration coefficients, (c[6]) → void
    printf("C1 %lu C2 %lu C3 %lu C4 %lu C5 %lu C6 %lu\r\n", (unsigned long)(k[0]), (unsigned long)(k[1]), (unsigned long)(k[2]), (unsigned long)(k[3]), (unsigned long)(k[4]), (unsigned long)(k[5]));
    printf("crc ok %lu serial %lu\r\n", (unsigned long)(sensor.prom_crc_ok()), (unsigned long)(sensor.serial_code())); // Verify the PROM CRC-4, () → bool

    // --- Barometric altimeter / variometer: 20 s, one reading per second ---
    // Altitude relative to the first reading; the climb rate is the change per second.
    float reference = sensor.read_pressure();                       // Read pressure only, () → float Pa
    float previous = 0, lowest = 100;
    for (int i = 0; i < 20; i++) {
        sensor.read(t, p);                                          // Read temperature and pressure, (temperature_c&, pressure_pa&) → bool
        if (t < lowest) lowest = t;
        float height = sensor.altitude(reference);                  // Altitude from pressure, (sea_level_pa) → float m
        printf("%.2f C %.1f Pa %.2f m %.2f m/s\r\n", (double)(t), (double)(p), (double)(height), (double)(i == 0 ? 0.0f : height - previous));
        previous = height;
        HAL_Delay(1000);
    }

    // --- Noise and time versus oversampling: ten readings each ---
    // Higher ratios take longer per reading and spread less.
    static const uint16_t osrs[5] = {256, 512, 1024, 2048, 4096};
    for (int o = 0; o < 5; o++) {
        sensor.set_oversampling(osrs[o]);                           // Select oversampling ratio, (osr) → bool
        float lo = 1e9f, hi = -1e9f;
        for (int n = 0; n < 10; n++) {
            float v = sensor.read_pressure();                       // Read pressure only, () → float Pa
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        printf("OSR %lu spread %.1f Pa\r\n", (unsigned long)(osrs[o]), (double)(hi - lo));
    }
    sensor.set_oversampling(4096);

    // --- Raw codes with and without the second-order correction ---
    sensor.read_raw(d1, d2);                                        // Read raw ADC codes, (d1&, d2&) → bool
    printf("raw D1 %lu D2 %lu\r\n", (unsigned long)(d1), (unsigned long)(d2));
    sensor.compensate(d1, d2, t, p, false);                         // Compensate raw codes, (d1, d2, temperature_c&, pressure_pa&, second_order) → void
    printf("first order  %.2f C %.1f Pa\r\n", (double)(t), (double)(p));
    sensor.compensate(d1, d2, t, p);
    printf("second order %.2f C %.1f Pa\r\n", (double)(t), (double)(p));
    printf("lowest temperature seen %.2f C\r\n", (double)(lowest));

    // --- Non-blocking conversion, then reset ---
    sensor.start_conversion(MS5607Full::PRESSURE);                  // Start a conversion, (kind, osr=0) → bool
    HAL_Delay(12);
    sensor.read_adc(adc);                                           // Read the ADC result, (value&) → bool
    printf("D1 %lu\r\n", (unsigned long)(adc));
    sensor.reset();                                                 // Reset and reload PROM, () → bool
    while (1) {}
}
