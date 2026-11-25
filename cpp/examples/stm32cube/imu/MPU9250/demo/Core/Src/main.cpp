#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "MPU9250.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, 0x68);
    I2CConnectionSTM32Cube magConnection(&hi2c1, 0x0C);   // AK8963, same bus, reached via I²C bypass

    // --- Configure for tilt and heading estimation ---
    // ±4g / ±500dps trade sensitivity for headroom against sharper motion than
    // the ±2g / ±250dps defaults tolerate; 16-bit continuous magnetometer mode
    // keeps a fresh heading available on every poll.
    MPU9250Full imu(connection, magConnection);       // Create MPU9250 driver, (connection, magConnection) → void
    imu.configure_accel(1);                           // Configure accel range, (full_scale=0) → void
    imu.configure_gyro(1);                            // Configure gyro range, (full_scale=0) → void
    imu.enable_mag(16, 6);                            // Initialize magnetometer, (bits=16, mode=6) → void

    HAL_Delay(2000);

    printf("roll     pitch    heading  |accel|  |gyro|\r\n");

    while (1) {
        // gate reads on data_ready so each sample reflects a fresh conversion
        while (!imu.data_ready()) {                   // Check data ready flag, () → bool
        }

        float ax, ay, az, gx, gy, gz, mx, my, mz;
        imu.accel(ax, ay, az);                        // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
        imu.gyro(gx, gy, gz);                         // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
        imu.mag(mx, my, mz);                          // Read 3-axis magnetic field, (float&, float&, float&) → void µT

        // --- Compute tilt angles from the accelerometer gravity vector ---
        // roll and pitch are reliable when the device is quasi-static;
        // gyro magnitude indicates how fast the board is being rotated.
        float roll  = atan2f(ay, az) * 180.0f / 3.141592653589793f;
        float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / 3.141592653589793f;

        // --- Compute magnetic heading (simplified, no tilt compensation) ---
        // Magnetometer axes differ from accel/gyro axes; user must account for this in fusion.
        float heading = atan2f(my, mx) * 180.0f / 3.141592653589793f;

        float accel_mag = sqrtf(ax * ax + ay * ay + az * az);
        float gyro_mag  = sqrtf(gx * gx + gy * gy + gz * gz);

        printf("%.1f      %.1f      %.1f      %.3f    %.3f\r\n", roll, pitch, heading, accel_mag, gyro_mag);
        HAL_Delay(100);
    }
}
