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
    float h, r, p, qw, qx, qy, qz, x, y, z;
    uint8_t sys_c, gyr_c, acc_c, mag_c, status, error;
    uint8_t blob[22];
    uint8_t uid[16];
    uint8_t chip_id, acc_id, mag_id, gyr_id, bootloader;
    uint16_t sw_revision;
    bool st_acc, st_mag, st_gyr, st_mcu;

    imu.euler(h, r, p);                                                 // Read fused Euler angles, (heading, roll, pitch) → void rad
                                                                        // burst read of 0x1A…0x1F, raw / 900
    imu.quaternion(qw, qx, qy, qz);                                     // Read orientation quaternion, (w, x, y, z) → void
                                                                        // raw / 16384
    imu.linear_acceleration(x, y, z);                                   // Read gravity-free acceleration, (x, y, z) → void m/s²
                                                                        // raw / 100
    imu.gravity(x, y, z);                                               // Read gravity vector, (x, y, z) → void m/s²
                                                                        // raw / 100
    imu.acceleration(x, y, z);                                          // Read raw accelerometer, (x, y, z) → void m/s²
                                                                        // raw / 100
    imu.magnetic_field(x, y, z);                                        // Read raw magnetometer, (x, y, z) → void µT
                                                                        // raw / 16
    imu.angular_rate(x, y, z);                                          // Read raw gyroscope, (x, y, z) → void rad/s
                                                                        // raw / 900
    printf("temperature %d C\r\n", (int)imu.temperature());
    // imu.temperature() → int8_t °C, signed 8 bit, 1 LSB = 1 °C
    imu.calibration_status(sys_c, gyr_c, acc_c, mag_c);                 // Read calibration status, (sys, gyr, acc, mag) → void
                                                                        // each 0 (uncalibrated) … 3 (calibrated)
    printf("calibrated %u\r\n", (unsigned)imu.is_calibrated());
    // imu.is_calibrated() → bool, true when all four values are 3

    printf("mode %u\r\n", (unsigned)imu.get_mode());
    // imu.get_mode() → uint8_t, 0x0C = NDOF
    imu.set_mode(BNO055Full::MODE_IMU);                                 // Select operation mode, (mode) → void
                                                                        // via CONFIGMODE, 19 ms + 7 ms waits
    imu.set_mode(BNO055Full::MODE_NDOF);                                // Select operation mode, (mode) → void
                                                                        // back to 9-DOF fusion

    imu.get_calibration(blob);                                          // Read offsets and radii, (out[22]) → void
                                                                        // 0x55…0x6A, read in CONFIGMODE
    imu.set_calibration(blob);                                          // Restore offsets and radii, (data[22]) → void
                                                                        // written in CONFIGMODE
    imu.set_axis_remap(0x24, 0x00);                                     // Remap axes, (config, sign) → void
                                                                        // 0x24/0x00 = identity placement
    imu.set_power_mode(BNO055Full::POWER_NORMAL);                       // Select power mode, (mode) → void
                                                                        // normal, low power or suspend
    imu.set_temperature_source(BNO055Full::TEMP_SOURCE_ACCELEROMETER);  // Select temperature sensor, (source) → void
                                                                        // accelerometer or gyroscope
    imu.use_external_crystal(false);                                    // Select clock source, (on) → void
                                                                        // internal oscillator unless a crystal is fitted

    imu.self_test(st_acc, st_mag, st_gyr, st_mcu);                      // Run self-test, (acc, mag, gyr, mcu) → void
                                                                        // true = passed, mode restored afterwards
    imu.system_status(status, error);                                   // Read system status, (status, error) → void
                                                                        // SYS_STATUS 5 = fusion running, SYS_ERR 0 = none
    imu.revisions(chip_id, acc_id, mag_id, gyr_id, sw_revision, bootloader);// Read revisions, (chip_id, acc_id, mag_id, gyr_id, sw_revision, bootloader) → void
                                                                        // identification registers 0x00…0x06
    imu.unique_id(uid);                                                 // Read unique ID, (out[16]) → void
                                                                        // 16 bytes from page 1

    imu.enable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);              // Enable interrupt source, (source) → void
                                                                        // INT_EN and INT_MSK on page 1
    printf("interrupt status 0x%02X\r\n", (unsigned)imu.poll_interrupt());
    // imu.poll_interrupt() → uint8_t, INT_STA, cleared on read
    imu.on_interrupt(on_int);                                           // Subscribe to interrupts, (callback) → void
                                                                        // needs an INT pin on the connection; otherwise poll
    HAL_Delay(500);
    imu.off_interrupt();                                                // Unsubscribe, () → void
                                                                        // stops the callback
    imu.disable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);             // Disable interrupt source, (source) → void
                                                                        // clears the INT_EN and INT_MSK bits

    imu.reset();                                                        // Reset the chip, () → void
                                                                        // RST_SYS, waits 650 ms, re-enters NDOF
    printf("done\r\n");
    while (true) HAL_Delay(1000);
}
