#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LSM303.h"

static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;
static int passed = 0;
static int failed = 0;

extern "C" void SysTick_Handler(void) {
    HAL_IncTick();
}

extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(ptr), static_cast<uint16_t>(len), HAL_MAX_DELAY);
    return len;
}

static void check_true(bool cond, const char* label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

static void gpio_clocks_init(void) {
    __HAL_RCC_GPIOA_CLK_ENABLE();
    __HAL_RCC_GPIOB_CLK_ENABLE();
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
    HAL_Delay(100);

    // Accelerometer slave (0x19) and magnetometer slave (0x1E) share the
    // initialised I2C1 handle.
    I2CConnectionSTM32Cube accel_connection(&hi2c1, 0x19);
    I2CConnectionSTM32Cube mag_connection(&hi2c1, 0x1E);

    // Identity check: IRA/IRB/IRC = 0x48 0x34 0x33 ("H43").
    uint8_t id[3] = {0, 0, 0};
    mag_connection.read(0x0A, id, 3);                        // Read mag identity, (reg, buf, len) → 0x48 0x34 0x33
    check_true(mag_connection.error_count() == 0, "i2c_no_hal_errors_on_mag_identity_read");
    check_true(id[0] == 0x48 && id[1] == 0x34 && id[2] == 0x33, "mag_identity_is_H43");
    check_true(accel_connection.error_count() == 0, "i2c_no_hal_errors_on_accel_slave");

    LSM303Minimal sensor(accel_connection, mag_connection);  // Create LSM303 driver, (accel, mag connections)

    float ax, ay, az;
    sensor.read_acceleration(ax, ay, az);                     // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(ax == ax && ay == ay && az == az, "read_acceleration_returns_floats");
    float amag = sqrtf(ax * ax + ay * ay + az * az);
    check_true(amag >= 0.5f && amag <= 1.5f, "acceleration_magnitude_near_1g");

    float mx, my, mz;
    sensor.read_magnetic(mx, my, mz);                         // Read 3-axis magnetic field, (x, y, z) → µT, µT, µT
    check_true(mx == mx && my == my && mz == mz, "read_magnetic_returns_floats");
    float bmag = sqrtf(mx * mx + my * my + mz * mz);
    check_true(bmag >= 5.0f && bmag <= 200.0f, "magnetic_magnitude_plausible");

    LSM303Full sensor_full(accel_connection, mag_connection);  // Create LSM303 Full driver, (accel, mag connections)
    sensor_full.set_range(4);                                // Set accelerometer range, (range_g) → g
    sensor_full.read_acceleration(ax, ay, az);               // Read 3-axis acceleration, (x, y, z) → g, g, g
    check_true(ax == ax && ay == ay && az == az, "read_acceleration_after_set_range_4g");

    sensor_full.set_mag_range(470);                          // Set magnetometer range, (range_ut) → None
    sensor_full.read_magnetic(mx, my, mz);                   // Read 3-axis magnetic field, (x, y, z) → µT, µT, µT
    check_true(mx == mx && my == my && mz == mz, "read_magnetic_after_set_mag_range");

    check_true(accel_connection.error_count() == 0 && mag_connection.error_count() == 0,
               "i2c_no_hal_errors_overall");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
