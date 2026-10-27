#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LSM303.h"

#define LOG(...) printf(__VA_ARGS__)
static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;

extern "C" void SysTick_Handler(void) { HAL_IncTick(); }
extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, (uint8_t*)ptr, (uint16_t)len, HAL_MAX_DELAY);
    return len;
}

static void lsm303_sleep_ms(uint32_t ms) { HAL_Delay(ms); }
static uint32_t lsm303_now_ms() { return HAL_GetTick(); }
static void on_int(uint16_t status) { printf("interrupt status: 0x%04X\n", status); }

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
    HAL_Delay(100);
    I2CConnectionSTM32Cube accel_connection(&hi2c1, 0x19);
    I2CConnectionSTM32Cube mag_connection(&hi2c1, 0x1E);
    LSM303Full compass(accel_connection, mag_connection);
    static const char* NAMES[16] = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                                    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
    compass.set_range(2);                                   // Set accelerometer range, (range_g) -> g
    compass.set_mode(2);                                    // Set accelerometer mode, (mode) -> None
    compass.set_data_rate(50);                              // Set accelerometer data rate, (rate_hz) -> Hz
    compass.set_mag_range(130);                             // Set magnetometer range, (range_ut) -> uT
    compass.set_mag_data_rate(15.0f);                       // Set magnetometer data rate, (rate_hz) -> Hz

    compass.set_click(0, 0x04, 0.8f, 20, 40, 200);          // Configure click detection, (single, double_, threshold_g, time_limit_ms, latency_ms, window_ms) -> None
    compass.set_free_fall(1, 0.35f, 60);                    // Configure free-fall, (generator, threshold_g, duration_ms) -> None
    compass.set_latch(1, true);                             // Latch generator 1, (generator, enabled) -> None
    compass.enable_interrupt(LSM303Full::SOURCE_CLICK, 1);  // Route source to INT1, (source, pin) -> None
    compass.enable_interrupt(LSM303Full::SOURCE_IA1, 1);    // Route source to INT1, (source, pin) -> None
    compass.set_fifo(LSM303Full::FIFO_STREAM, 16, false);   // Configure FIFO, (mode, watermark, trigger_int2) -> None

    uint32_t start = lsm303_now_ms();
    uint32_t last = start;
    while (lsm303_now_ms() - start < 60000) {
        uint32_t now = lsm303_now_ms();
        if (now - last >= 1000) {
            uint8_t level = compass.fifo_level();           // Read FIFO level, () -> int
            float xb[32], yb[32], zb[32];
            uint8_t n = compass.read_fifo(xb, yb, zb, 32);  // Drain FIFO, (x_buf, y_buf, z_buf, max_samples) -> int
            float ax = 0, ay = 0, az = 0;
            if (n > 0) {
                for (uint8_t i = 0; i < n; i++) { ax += xb[i]; ay += yb[i]; az += zb[i]; }
                ax /= n; ay /= n; az /= n;
            } else {
                compass.read_acceleration(ax, ay, az);      // Read 3-axis acceleration, (x, y, z) -> g, g, g
            }
            float mx, my, mz;
            compass.read_magnetic(mx, my, mz);              // Read 3-axis magnetic field, (x, y, z) -> uT, uT, uT
            float field = sqrtf(mx * mx + my * my + mz * mz);
            float pitch = atan2f(ax, sqrtf(ay * ay + az * az)) * 57.2957795f;
            float roll = atan2f(ay, az) * 57.2957795f;
            float hdg = compass.heading(true);              // Compute compass heading, (tilt_compensate) -> deg
            const char* name = NAMES[(int)((hdg + 11.25f) / 22.5f) % 16];
            float temp = compass.read_temperature_delta();  // Read relative temperature, () -> float C
            LOG("%5.1f deg %s  pitch=%+.1f roll=%+.1f |B|=%.1f uT  T=%+.1f C  fifo=%d\n",
                hdg, name, pitch, roll, field, temp, level);

            uint16_t isr = compass.poll_interrupt();        // Read interrupt status, () -> int
            if (isr & LSM303Full::SOURCE_CLICK) LOG("DOUBLE TAP\n");
            if (isr & LSM303Full::SOURCE_IA1)   LOG("FREE FALL\n");
            last = now;
        }
        lsm303_sleep_ms(10);
    }
    LOG("done\n");
    return 0;
}
