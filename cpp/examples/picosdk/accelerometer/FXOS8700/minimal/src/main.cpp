#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "FXOS8700.h"

static void sleepMs(uint32_t ms) { sleep_ms(ms); }
static void printVec(const char* label, float x, float y, float z, const char* unit) {
    printf("%s x=%.3f y=%.3f z=%.3f %s\n", label, x, y, z, unit);
}

int main(void) {
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    stdio_init_all();

    I2CConnectionPicoSDK connection(i2c0, 0x1E);
    FXOS8700Minimal compass(connection);                    // Create FXOS8700 driver, (connection)
    for (int i = 0; i < 10; i++) {
        float ax, ay, az, mx, my, mz;
        compass.readAccel(ax, ay, az);                      // Read acceleration, (x, y, z) → g, g, g
        compass.readMag(mx, my, mz);                        // Read magnetic field, (x, y, z) → µT, µT, µT
        printVec("accel", ax, ay, az, "g");
        printVec("mag", mx, my, mz, "uT");
        sleepMs(500);
    }
    return 0;
}
