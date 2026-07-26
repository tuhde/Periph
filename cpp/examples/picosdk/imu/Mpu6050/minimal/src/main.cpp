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
    Mpu6050Minimal mpu6050(transport);

    stdio_init_all();
    while (true) {

    float ax, ay, az;
    float gx, gy, gz;
    imu.accel(ax, ay, az);                               // Read 3-axis acceleration, (x, y, z) → m/s²
    imu.gyro(gx, gy, gz);                                // Read 3-axis angular rate, (x, y, z) → rad/s
    printf("accel: "); printf("%f", ax); printf(" "); printf("%f", ay); printf(" "); printf("%f", az);
    printf("  gyro: "); printf("%f", gx); printf(" "); printf("%f", gy); printf(" "); printf("%f\n", gz);
    sleep_ms(100);
        sleep_ms(10);
    }

    return 0;
}
