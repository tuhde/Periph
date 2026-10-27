#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cmath>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "LSM303.h"

#define PRINTF(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) usleep((ms) * 1000)

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : LSM303Minimal::I2C_ADDRESS_ACCEL;
    I2CConnectionLinux accel_connection(bus, addr);
    I2CConnectionLinux mag_connection(bus, LSM303Minimal::I2C_ADDRESS_MAG);
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
