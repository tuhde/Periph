#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "Mpu6050.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x68);
    Mpu6050Full mpu6050(transport);

    stdio_init_all();

    // --- Configure for motion logging with moderate dynamic range ---
    // ±4g captures typical tilting and handling forces without clipping;
    // ±500 dps covers fast rotations while retaining sub-degree resolution.
    imu.configure_accel(1);                               // Configure accel range, (full_scale=0) → None
    imu.configure_gyro(1);                                // Configure gyro range, (full_scale=0) → None

    printf("roll     pitch    |accel|    |gyro|\n");
    while (true) {

    // gate reads on data_ready so each sample reflects a fresh conversion
    while (!imu.data_ready()) {}                          // Check data ready flag, () → bool

    float ax, ay, az;
    float gx, gy, gz;
    imu.accel(ax, ay, az);                                // Read 3-axis acceleration, (x, y, z) → m/s²
    imu.gyro(gx, gy, gz);                                 // Read 3-axis angular rate, (x, y, z) → rad/s

    // --- Compute tilt angles from the accelerometer gravity vector ---
    // roll and pitch are reliable when the device is quasi-static;
    // gyro magnitude indicates how fast the board is being rotated.
    float roll  = atan2f(ay, az) * 180.0f / 3.141592653589793f;
    float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / 3.141592653589793f;
    float accel_mag = sqrtf(ax * ax + ay * ay + az * az);
    float gyro_mag  = sqrtf(gx * gx + gy * gy + gz * gz);

    printf("%.1f", roll);    printf("  ");
    printf("%.1f", pitch);   printf("  ");
    printf("%.3f", accel_mag); printf("  ");
    printf("%.3f\n", gyro_mag);

    sleep_ms(100);
        sleep_ms(10);
    }

    return 0;
}
