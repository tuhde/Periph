#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LSM303.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK accel_connection(i2c0, 0x19);
    I2CConnectionPicoSDK mag_connection(i2c0, 0x1E);
    LSM303Minimal sensor(accel_connection, mag_connection);  // Create LSM303 driver, (accel, mag connections)

    stdio_init_all();
    while (true) {
        float ax, ay, az;
        sensor.read_acceleration(ax, ay, az);                 // Read 3-axis acceleration, (x, y, z) → g, g, g
        float mx, my, mz;
        sensor.read_magnetic(mx, my, mz);                     // Read 3-axis magnetic field, (x, y, z) → µT, µT, µT
        printf("a=(%.3f %.3f %.3f) g  m=(%.1f %.1f %.1f) uT\n",
               (double)ax, (double)ay, (double)az, (double)mx, (double)my, (double)mz);
        sleep_ms(100);
    }

    return 0;
}
