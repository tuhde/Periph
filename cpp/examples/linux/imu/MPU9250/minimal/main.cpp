#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MPU9250.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x68;
    I2CConnectionLinux connection(bus, addr);

    MPU9250Minimal imu(connection);                                       // Create MPU9250 driver, (connection)

    while (true) {
        float ax, ay, az, gx, gy, gz;
        imu.accel(ax, ay, az);                                            // Read 3-axis acceleration, (float&, float&, float&) → void m/s²
        imu.gyro(gx, gy, gz);                                             // Read 3-axis angular rate, (float&, float&, float&) → void rad/s
        printf("a=%.3f,%.3f,%.3f  g=%.3f,%.3f,%.3f\n", ax, ay, az, gx, gy, gz);
        usleep(100000);
    }
    return 0;
}
