#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Mpl3115a2.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x60);
    MPL3115A2Full sensor(connection);                              // Create MPL3115A2 driver, (connection) → void
    float p = 0, t = 0, alt = 0;
    sensor.init();                                                 // Reset and apply defaults, () → bool
    // --- Weather and altitude station ---
    // Sets the local sea-level pressure, takes three barometer readings, switches to
    // altimeter mode for three altitude readings, collects 16 samples in the circular
    // FIFO and prints their minimum, maximum and mean, then arms a temperature
    // threshold on INT1 and reports the captured extremes.
    sensor.set_sea_level_pressure(101325.0f);                      // Sea-level reference, (pa) → bool
    for (int i = 0; i < 3; i++) {
        sensor.read(p, t);                                         // Read pressure and temperature, (pressure_pa&, temperature_c&) → bool
        printf("%.2f Pa %.2f C\r\n", (double)(p), (double)(t));
        HAL_Delay(5000);
    }
    for (int i = 0; i < 3; i++) {
        sensor.read_altitude(alt);                                 // Read altitude, (altitude_m&) → bool
        printf("altitude %.2f m\r\n", (double)(alt));
    }
    sensor.set_mode(false);                                        // Back to barometer, (altimeter) → bool
    sensor.set_oversample(16);                                     // Faster conversions, (ratio) → bool
    sensor.fifo_setup(MPL3115A2Full::FIFO_CIRCULAR, 16);           // Circular FIFO, watermark 16, (mode, watermark) → bool
    sensor.start_continuous(0);                                    // One sample per second, (step_exp 0-15) → bool
    bool overflow = false, watermark = false;
    uint8_t count = 0;
    while (!watermark) {
        HAL_Delay(1000);
        sensor.fifo_status(overflow, watermark, count);
    }
    float fv[32], ft[32];
    uint8_t n = sensor.fifo_read(fv, ft, 32);                      // Drain the FIFO, (values*, temperatures*, max) → uint8_t
    float lo = fv[0], hi = fv[0], sum = 0;
    for (uint8_t i = 0; i < n; i++) { if (fv[i] < lo) lo = fv[i]; if (fv[i] > hi) hi = fv[i]; sum += fv[i]; }
    printf("n=%lu min %.2f max %.2f mean %.2f Pa\r\n", (unsigned long)(n), (double)(lo), (double)(hi), (double)(sum / (n ? n : 1)));
    sensor.set_target(0.0f, 0.0f, (int8_t)(ft[n - 1] + 1), 0);     // Temperature target, (value, window, temperature_c, temperature_window) → bool
    sensor.enable_interrupt(MPL3115A2Full::SOURCE_TTH, 1);         // Route temperature threshold to INT1, (source, pin) → bool
    printf("INT_SOURCE 0x%02X\r\n", (unsigned)(sensor.poll_interrupt()));
    sensor.stop_continuous();                                      // Back to STANDBY, () → bool
    sensor.fifo_setup(MPL3115A2Full::FIFO_OFF);                    // FIFO off, (mode) → bool
    float pmin = 0, pmax = 0, tmin = 0, tmax = 0;
    sensor.read_min_max(pmin, pmax, tmin, tmax);                   // Captured extremes, (p_min&, p_max&, t_min&, t_max&) → bool
    printf("min %.2f Pa max %.2f Pa\r\n", (double)(pmin), (double)(pmax));
    while (1) {}
}
