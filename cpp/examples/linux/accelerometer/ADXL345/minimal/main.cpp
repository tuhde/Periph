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

    while (true) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        printf("x=%.3f y=%.3f z=%.3f g\n", (double)x, (double)y, (double)z);
        usleep(100000);
    }
    return 0;
}