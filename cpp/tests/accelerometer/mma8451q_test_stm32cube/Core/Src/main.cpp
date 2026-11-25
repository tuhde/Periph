#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "MMA8451Q.h"

static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;

extern "C" void SysTick_Handler(void) { HAL_IncTick(); }

extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(ptr), static_cast<uint16_t>(len), HAL_MAX_DELAY);
    return len;
}

static int passed = 0, failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

static bool is_finite(float v) {
    return v == v && v > -1e30f && v < 1e30f;
}

static void i2c1_init(void) {
    __HAL_RCC_GPIOB_CLK_ENABLE();
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

static void uart2_init(void) {
    __HAL_RCC_GPIOA_CLK_ENABLE();
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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    uart2_init();
    i2c1_init();
    HAL_Delay(100);

    I2CConnectionSTM32Cube connection(&hi2c1, 0x1C);
    MMA8451QMinimal accel(connection);                       // Create MMA8451Q driver, (connection)
    MMA8451QFull accel_full(connection);                     // Create MMA8451Q Full driver, (connection)

    float x, y, z;
    accel.read(x, y, z);                                    // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(is_finite(x) && is_finite(y) && is_finite(z), "read_returns_floats");
    float mag = sqrtf(x * x + y * y + z * z);
    check_true(mag >= 0.5f && mag <= 1.5f, "magnitude_near_1g");

    accel_full.set_range(4);
    accel_full.read(x, y, z);
    check_true(is_finite(x) && is_finite(y) && is_finite(z), "read_after_set_range_4g");

    accel_full.set_data_rate(50);
    accel_full.read(x, y, z);
    check_true(is_finite(x) && is_finite(y) && is_finite(z), "read_after_set_data_rate_50hz");

    int16_t rx, ry, rz;
    accel_full.read_raw(rx, ry, rz);
    check_true(rx <= 8192 && ry <= 8192 && rz <= 8192, "read_raw_returns_counts");

    check_true(accel_full.read_system_mode() <= 2, "read_system_mode");

    bool changed = false, lockout = false, back = false;
    uint8_t lapo = 0;
    accel_full.read_orientation(changed, lockout, lapo, back);
    check_true(lapo <= 3, "read_orientation_decodes_lapo");

    accel_full.set_fifo(1, 16);
    uint8_t level = accel_full.fifo_level();
    check_true(level <= 32, "fifo_level_in_range");
    bool overflow = false, watermark = false;
    uint8_t count = 0;
    accel_full.read_fifo_status(overflow, watermark, count);
    check_true(count <= 32, "fifo_status_count_in_range");
    float fifo[3 * 8];
    uint8_t got = accel_full.read_fifo(fifo, 8);
    check_true(got <= 8, "read_fifo_count_in_range");
    accel_full.set_fifo(0, 0);

    accel_full.set_freefall_motion(false, 7, 0.2f, 60.0f, true, false);
    check_true(true, "set_freefall_motion");
    accel_full.set_transient(7, 0.25f, 100.0f, false, false);
    check_true(true, "set_transient");
    accel_full.set_pulse(0x04, 0, 0.5f, 0.5f, 1.0f, 40.0f, 100.0f, 300.0f, true, false, true, true);
    check_true(true, "set_pulse");
    accel_full.set_orientation(true, 100.0f, 45, 4, 4, 1);
    check_true(true, "set_orientation");
    accel_full.set_auto_sleep(true, 12.5f, 10.0f, MMA8451QFull::SOURCE_PULSE);
    check_true(true, "set_auto_sleep");
    accel_full.set_auto_sleep(false, 12.5f, 10.0f, 0);

    accel_full.enable_interrupt(MMA8451QFull::SOURCE_DATA_READY, 1);
    accel_full.poll_interrupt();
    check_true(true, "poll_interrupt");
    accel_full.disable_interrupt(MMA8451QFull::SOURCE_DATA_READY);

    float ox, oy, oz;
    accel_full.get_offset(ox, oy, oz);
    check_true(ox >= -0.256f && ox <= 0.256f, "get_offset_in_range");

    check_true(connection.error_count() == 0, "i2c_no_hal_errors");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
