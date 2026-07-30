#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MPU6050.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x68;
    I2CConnectionLinux connection(bus, addr);

    MPU6050Full mpu(connection);                                            // Create MPU-6050 driver, (connection)

    // --- Tilt detector ---
    // Logs a warning whenever the Z-axis acceleration deviates more than
    // 0.3 g from 1 g (flat), indicating significant tilt or motion.
    mpu.set_accel_range(0);                                                // Set accelerometer full-scale, (range 0 → ±2 g) → void
    while (true) {
        float ax, ay, az, gx, gy, gz;
        mpu.read(ax, ay, az, gx, gy, gz);                                  // Read accel+gyro, (ax,ay,az m/s², gx,gy,gz °/s) → void
        float tilt = __builtin_fabsf(az - 9.81f);
        printf("az=%.3f  %s\n", az, tilt > 2.94f ? "TILTED" : "level");
        usleep(100000);
    }
    return 0;
}
