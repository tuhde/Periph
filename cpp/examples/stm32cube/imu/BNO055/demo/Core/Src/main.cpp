#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "BNO055.h"

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

static void on_int(uint8_t status) { printf("INT 0x%02X\n", (unsigned)status); }

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x29);
    BNO055Full imu(connection);                                         // Create BNO055 driver, (connection) → void
    float h, r, p, qw, qx, qy, qz, lx, ly, lz, gx, gy, gz, ax, ay, az, mx, my, mz, rx, ry, rz;
    uint8_t sys_c, gyr_c, acc_c, mag_c, status, error;
    uint8_t blob[22];
    uint8_t uid[16];
    uint8_t chip_id, acc_id, mag_id, gyr_id, bootloader;
    uint16_t sw_revision;
    bool st_acc, st_mag, st_gyr, st_mcu;

    // --- Identify the part ---
    imu.revisions(chip_id, acc_id, mag_id, gyr_id, sw_revision, bootloader);// Read revisions, (chip_id, acc_id, mag_id, gyr_id, sw_revision, bootloader) → void
    printf("chip 0x%02X, SW %u.%u, bootloader %u\r\n", (unsigned)chip_id, (unsigned)(sw_revision >> 8), (unsigned)(sw_revision & 0xFF), (unsigned)bootloader);
    imu.unique_id(uid);                                                 // Read unique ID, (out[16]) → void
    printf("unique id %02X%02X%02X%02X...\r\n", (unsigned)uid[0], (unsigned)uid[1], (unsigned)uid[2], (unsigned)uid[3]);

    // --- Orientation monitor until fully calibrated (move the board in a figure eight) ---
    while (true) {
        imu.euler(h, r, p);                                             // Read fused Euler angles, (heading, roll, pitch) → void rad
        imu.quaternion(qw, qx, qy, qz);                                 // Read orientation quaternion, (w, x, y, z) → void
        imu.linear_acceleration(lx, ly, lz);                            // Read gravity-free acceleration, (x, y, z) → void m/s²
        imu.gravity(gx, gy, gz);                                        // Read gravity vector, (x, y, z) → void m/s²
        imu.calibration_status(sys_c, gyr_c, acc_c, mag_c);             // Read calibration status, (sys, gyr, acc, mag) → void
        printf("euler %.2f %.2f %.2f  quat %.3f %.3f %.3f %.3f  cal %u%u%u%u\r\n", h, r, p, qw, qx, qy, qz, (unsigned)sys_c, (unsigned)gyr_c, (unsigned)acc_c, (unsigned)mag_c);
        printf("linear %.2f %.2f %.2f  gravity %.2f %.2f %.2f\r\n", lx, ly, lz, gx, gy, gz);
        if (imu.is_calibrated()) break;                                  // Check full calibration, () → bool
        HAL_Delay(100);
    }

    // --- Save the calibration, relative mode for 5 s, restore it ---
    imu.get_calibration(blob);                                          // Read offsets and radii, (out[22]) → void
    imu.set_mode(BNO055Full::MODE_IMU);                                 // Select operation mode, (mode) → void
    printf("mode %u\r\n", (unsigned)imu.get_mode());
    HAL_Delay(5000);
    imu.set_mode(BNO055Full::MODE_NDOF);                                // Select operation mode, (mode) → void
    imu.set_calibration(blob);                                          // Restore offsets and radii, (data[22]) → void

    // --- Self-test, temperature and configuration ---
    imu.self_test(st_acc, st_mag, st_gyr, st_mcu);                      // Run self-test, (acc, mag, gyr, mcu) → void
    printf("self-test acc %u mag %u gyr %u mcu %u\r\n", (unsigned)st_acc, (unsigned)st_mag, (unsigned)st_gyr, (unsigned)st_mcu);
    printf("temperature %d C\r\n", (int)imu.temperature());
    imu.set_temperature_source(BNO055Full::TEMP_SOURCE_GYROSCOPE);      // Select temperature sensor, (source) → void
    imu.set_axis_remap(0x24, 0x00);                                     // Remap axes, (config, sign) → void
    imu.set_power_mode(BNO055Full::POWER_NORMAL);                       // Select power mode, (mode) → void
    imu.use_external_crystal(false);                                    // Select clock source, (on) → void

    // --- Raw sensors in AMG mode ---
    imu.set_mode(BNO055Full::MODE_AMG);                                 // Select operation mode, (mode) → void
    imu.acceleration(ax, ay, az);                                       // Read raw accelerometer, (x, y, z) → void m/s²
    imu.magnetic_field(mx, my, mz);                                     // Read raw magnetometer, (x, y, z) → void µT
    imu.angular_rate(rx, ry, rz);                                       // Read raw gyroscope, (x, y, z) → void rad/s
    printf("acc %.2f %.2f %.2f  mag %.1f %.1f %.1f  gyr %.3f %.3f %.3f\r\n", ax, ay, az, mx, my, mz, rx, ry, rz);

    // --- Interrupts: accelerometer/fusion data ready ---
    imu.set_mode(BNO055Full::MODE_NDOF);                                // Select operation mode, (mode) → void
    imu.enable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);              // Enable interrupt source, (source) → void
    imu.on_interrupt(on_int);                                           // Subscribe to interrupts, (callback) → void
    HAL_Delay(500);
    imu.off_interrupt();                                                // Unsubscribe, () → void
    imu.disable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);             // Disable interrupt source, (source) → void
    printf("interrupt status 0x%02X\r\n", (unsigned)imu.poll_interrupt());
    imu.system_status(status, error);                                   // Read system status, (status, error) → void
    printf("system status %u, error %u\r\n", (unsigned)status, (unsigned)error);
    imu.reset();                                                        // Reset the chip, () → void
    while (true) HAL_Delay(1000);
}
