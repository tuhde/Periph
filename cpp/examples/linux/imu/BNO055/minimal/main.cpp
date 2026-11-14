#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BNO055.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x29;
    I2CConnectionLinux connection(bus, addr);

    BNO055Minimal imu(connection);                                      // Create BNO055 driver, (connection) → void
    float h, r, p;

    while (true) {
        imu.euler(h, r, p);                                             // Read fused Euler angles, (heading, roll, pitch) → void rad
        printf("heading %.2f rad, roll %.2f, pitch %.2f\n", h, r, p);
        usleep(100 * 1000);
    }
    return 0;
}
