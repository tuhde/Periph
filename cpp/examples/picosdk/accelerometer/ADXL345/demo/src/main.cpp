#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "ADXL345.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x53);
    ADXL345Minimal accel(connection);                       // Create ADXL345 driver, (connection, spi=false)

    stdio_init_all();

    // --- 50-sample stationary tilt characterization at 10 Hz ---
    // With the sensor flat and the Z axis up, gravity should project entirely
    // onto Z. Tilting the board visibly redistributes the 1 *g* magnitude
    // across X and Y; the total vector magnitude stays near 1 *g*.
    const int SAMPLES = 50;

    float mag_min = 1e9f, mag_max = -1e9f;

    for (int n = 0; n < SAMPLES; n++) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        float mag = sqrtf(x * x + y * y + z * z);
        if (mag < mag_min) mag_min = mag;
        if (mag > mag_max) mag_max = mag;
        printf("%2d  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g\n",
               n, (double)x, (double)y, (double)z, (double)mag);
        sleep_ms(100);
    }

    printf("min |a|=%.3f g  max |a|=%.3f g\n", (double)mag_min, (double)mag_max);
    while (true) sleep_ms(1000);
    return 0;
}