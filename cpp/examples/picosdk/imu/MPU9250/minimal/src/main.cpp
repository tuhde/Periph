#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "hardware/i2c.h"
#include "I2CConnectionPicoSDK.h"
#include "MPU9250.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK connection(i2c0, 0x68);
    MPU9250Minimal imu(connection);

    stdio_init_all();
    sleep_ms(2000);

    while (1) {
        float ax, ay, az, gx, gy, gz;
        imu.accel(ax, ay, az);                            // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
        imu.gyro(gx, gy, gz);                             // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
        printf("accel: %.2f %.2f %.2f  gyro: %.2f %.2f %.2f\n", ax, ay, az, gx, gy, gz);
        sleep_ms(100);
    }
    return 0;
}