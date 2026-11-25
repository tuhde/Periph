#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LSM303.h"

#define PRINTF(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) sleep_ms(ms)

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK accel_connection(i2c0, LSM303Minimal::I2C_ADDRESS_ACCEL);
    I2CConnectionPicoSDK mag_connection(i2c0, LSM303Minimal::I2C_ADDRESS_MAG);
    LSM303Minimal accel(accel_connection, mag_connection);  // Create LSM303 driver, (accel_connection, mag_connection)


    while (true) {
        float ax, ay, az;
        float mx, my, mz;
        accel.readAcceleration(ax, ay, az);      // Read acceleration, (x, y, z) → g, g, g
        accel.readMagnetic(mx, my, mz);            // Read magnetic field, (x, y, z) → uT, uT, uT
        PRINTF("accel x=%.4f y=%.4f z=%.4f g\n", (double)ax, (double)ay, (double)az);
        PRINTF("mag   x=%.2f y=%.2f z=%.2f uT\n", (double)mx, (double)my, (double)mz);
        SLEEP_MS(1000);
    }
    return 0;
}
