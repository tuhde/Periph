#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADXL345.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x53;
    I2CConnectionLinux connection(bus, addr);

    ADXL345Minimal accel(connection);                       // Create ADXL345 driver, (connection, spi=false)

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
        usleep(100000);
    }

    printf("min |a|=%.3f g  max |a|=%.3f g\n", (double)mag_min, (double)mag_max);
    return 0;
}