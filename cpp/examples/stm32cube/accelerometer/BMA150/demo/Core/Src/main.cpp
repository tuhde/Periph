#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "BMA150.h"

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
    gpioInit.Pin       = GPIO_PIN_2 | GPIO_PIN_3;
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
    gpioInit.Pin       = GPIO_PIN_8 | GPIO_PIN_9;
    gpioInit.Mode      = GPIO_MODE_AF_OD;
    gpioInit.Pull      = GPIO_PULLUP;
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

    I2CConnectionSTM32Cube connection(&hi2c1, 0x38);
    BMA150Full accel(connection);                       // Create BMA150 driver, (connection)

    // --- Configure ±8 g / 190 Hz and arm LG + HG latched interrupts ---
    // ±8 g gives 64 LSB/g, plenty of headroom for shock detection. 190 Hz
    // bandwidth is wide enough to capture a 2 ms high-g spike without
    // aliasing. Latched interrupts free the polling loop from having to
    // catch a transient.
    accel.set_range(8);                                  // Set measurement range, (range_g=2) → g
    accel.set_bandwidth(190);                            // Set bandwidth, (bandwidth_hz=25) → Hz
    accel.set_latch(true);                               // Set latched interrupts, (enabled=False) → None
    accel.set_low_g(0.4, 40);                            // Configure low-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → g, ms
    accel.set_high_g(4.0, 2);                            // Configure high-g, (threshold_g, duration_ms, hysteresis_g=0, counter=0) → g, ms

    uint32_t start = HAL_GetTick();
    uint32_t last_heartbeat = 0;
    uint32_t last_poll = 0;

    // --- 60-second free-fall / shock logger ---
    // User is expected to drop or shake the board at some point during
    // the 60 s window. Between events the magnitude sits at ≈1.00 g
    // (gravity). Each latched interrupt is reported with a timestamp,
    // the latest (x, y, z), temperature, and a free-fall or shock tag.
    while (HAL_GetTick() - start < 60000) {
        uint32_t now = HAL_GetTick();

        if (now - last_heartbeat >= 1000) {
            float x, y, z;
            accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
            float mag = sqrtf(x * x + y * y + z * z);
            float temp = accel.read_temperature();               // Read temperature, () → °C
            printf("%5lu  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g  T=%.1f C\r\n",
                   (unsigned long)((now - start) / 1000), (double)x, (double)y, (double)z, (double)mag, (double)temp);
            last_heartbeat = now;
        }

        if (now - last_poll >= 50) {
            uint8_t status = accel.poll_interrupt();             // Read STATUS, () → bitmask
            if (status & 0x08) {                                // STATUS_LG_LATCHED (bit 3)
                float x, y, z;
                accel.read(x, y, z);                            // Read 3-axis acceleration, (x, y, z) → g, g, g
                float temp = accel.read_temperature();           // Read temperature, () → °C
                printf("%5lu  FREE FALL detected  x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C\r\n",
                       (unsigned long)(now - start), (double)x, (double)y, (double)z, (double)temp);
                accel.clear_interrupt();                         // Clear latched interrupts, () → None
            }
            if (status & 0x04) {                                // STATUS_HG_LATCHED (bit 2)
                float x, y, z;
                accel.read(x, y, z);                            // Read 3-axis acceleration, (x, y, z) → g, g, g
                float temp = accel.read_temperature();           // Read temperature, () → °C
                printf("%5lu  SHOCK detected     x=%+.3f  y=%+.3f  z=%+.3f  T=%.1f C\r\n",
                       (unsigned long)(now - start), (double)x, (double)y, (double)z, (double)temp);
                accel.clear_interrupt();                         // Clear latched interrupts, () → None
            }
            last_poll = now;
        }

        HAL_Delay(10);
    }
    printf("done\r\n");
    while (true) HAL_Delay(1000);
}
