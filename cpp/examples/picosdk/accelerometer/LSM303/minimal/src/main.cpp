#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LSM303.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    stdio_init_all();

    I2CConnectionPicoSDK accelConn(i2c0, 0x19);             // Accelerometer connection at 0x19
    I2CConnectionPicoSDK magConn(i2c0, 0x1E);               // Magnetometer connection at 0x1E

    LSM303Minimal chip(accelConn, magConn);                 // Create LSM303 driver, (accel_conn, mag_conn) → sets ±2 g, 100 Hz, ±130 µT, 15 Hz

    while (true) {
        float ax, ay, az, mx, my, mz;
        chip.readAcceleration(ax, ay, az);                  // Read acceleration, (x, y, z) → g, g, g
        chip.readMagnetic(mx, my, mz);                      // Read magnetic field, (x, y, z) → µT, µT, µT
        printf("a=%.3f,%.3f,%.3f g  m=%.1f,%.1f,%.1f uT\n",
               (double)ax, (double)ay, (double)az,
               (double)mx, (double)my, (double)mz);
        sleep_ms(200);
    }

    return 0;
}
