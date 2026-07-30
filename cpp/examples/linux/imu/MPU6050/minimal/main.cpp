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

    MPU6050Minimal mpu(connection);                                         // Create MPU-6050 driver, (connection)

    while (true) {
        float ax, ay, az, gx, gy, gz;
        mpu.accel(ax, ay, az);                                             // Read 3-axis acceleration, (x, y, z) → m/s²
        mpu.gyro(gx, gy, gz);                                              // Read 3-axis angular rate, (x, y, z) → rad/s
        printf("a=%.3f,%.3f,%.3f  g=%.3f,%.3f,%.3f\n", ax, ay, az, gx, gy, gz);
        usleep(100000);
    }
    return 0;
}
