#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "BMA180.h"

static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;

extern "C" void SysTick_Handler(void) { HAL_IncTick(); }

extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(ptr), static_cast<uint16_t>(len), HAL_MAX_DELAY);
    return len;
}

static void gpio_clocks_init(void) {
    __HAL_RCC_GPIOA_CLK_ENABLE();
    __HAL_RCC_GPIOB_CLK_ENABLE();
}

static void uart2_init(void) {
    __HAL_RCC_USART2_CLK_ENABLE();
    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin = GPIO_PIN_2 | GPIO_PIN_3;
    gpioInit.Mode = GPIO_MODE_AF_PP;
    gpioInit.Pull = GPIO_NOPULL;
    gpioInit.Speed = GPIO_SPEED_FREQ_LOW;
    gpioInit.Alternate = GPIO_AF7_USART2;
    HAL_GPIO_Init(GPIOA, &gpioInit);
    huart2.Instance = USART2;
    huart2.Init.BaudRate = 115200;
    huart2.Init.WordLength = UART_WORDLENGTH_8B;
    huart2.Init.StopBits = UART_STOPBITS_1;
    huart2.Init.Parity = UART_PARITY_NONE;
    huart2.Init.Mode = UART_MODE_TX_RX;
    huart2.Init.HwFlowCtl = UART_HWCONTROL_NONE;
    huart2.Init.OverSampling = UART_OVERSAMPLING_16;
    HAL_UART_Init(&huart2);
}

static void i2c1_init(void) {
    __HAL_RCC_I2C1_CLK_ENABLE();
    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin = GPIO_PIN_8 | GPIO_PIN_9;
    gpioInit.Mode = GPIO_MODE_AF_OD;
    gpioInit.Pull = GPIO_PULLUP;
    gpioInit.Speed = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF4_I2C1;
    HAL_GPIO_Init(GPIOB, &gpioInit);
    hi2c1.Instance = I2C1;
    hi2c1.Init.ClockSpeed = 100000;
    hi2c1.Init.DutyCycle = I2C_DUTYCYCLE_2;
    hi2c1.Init.OwnAddress1 = 0;
    hi2c1.Init.AddressingMode = I2C_ADDRESSINGMODE_7BIT;
    hi2c1.Init.DualAddressMode = I2C_DUALADDRESS_DISABLE;
    hi2c1.Init.OwnAddress2 = 0;
    hi2c1.Init.GeneralCallMode = I2C_GENERALCALL_DISABLE;
    hi2c1.Init.NoStretchMode = I2C_NOSTRETCH_DISABLE;
    HAL_I2C_Init(&hi2c1);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube connection(&hi2c1, 0x40);
    BMA180Full accel(connection);                          // Create BMA180 Full driver, (connection)

    // --- Configure for tilt + tap + free-fall demo at low-noise, 40 Hz, ±2 g ---
    accel.set_bandwidth(40);                                // Set bandwidth, (bandwidth_hz) → Hz
    // --- Calibrate zero-g while the board sits level ---
    accel.calibrate_offset(0x07, 1);                       // Calibrate offset, (axes, mode) → None
    // --- Arm tap and free-fall detection with latching so we never miss an event ---
    accel.set_tap(0.5, 250);                               // Configure tap, (threshold_g, window_ms) → None
    accel.set_low_g(0.3, 40);                              // Configure low-g, (threshold_g, duration_ms) → None
    accel.set_latch(true);                                  // Set latched interrupts, (enabled) → None

    // --- Print tilt + temperature every 100 ms; poll interrupts for tap/free-fall ---
    for (int i = 0; i < 600; i++) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        float mag = sqrtf(x * x + y * y + z * z);
        float t   = accel.read_temperature();               // Read temperature, () → °C
        printf("|a|=%.3f g  T=%.1f C\r\n", (double)mag, (double)t);

        uint8_t flags = accel.poll_interrupt();            // Read STATUS_REG3, () → bitmask
        if (flags & 0x10) {
            printf("DOUBLE TAP\r\n");
            accel.clear_interrupt();                        // Clear latched interrupts, () → None
        }
        if (flags & 0x40) {
            printf("FREE FALL\r\n");
            accel.clear_interrupt();                        // Clear latched interrupts, () → None
        }
        HAL_Delay(100);
    }
    while (true) HAL_Delay(1000);
}