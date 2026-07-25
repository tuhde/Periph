#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "Mpu6050.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x68;
    I2CTransportLinux transport(bus, addr);

    Mpu6050Minimal mpu(transport);                                         // Create MPU-6050 driver, (transport)

    while (true) {
        float ax, ay, az, gx, gy, gz;
        mpu.read(ax, ay, az, gx, gy, gz);                                  // Read accel+gyro, (ax,ay,az m/s², gx,gy,gz °/s) → void
        printf("a=%.3f,%.3f,%.3f  g=%.3f,%.3f,%.3f\n", ax, ay, az, gx, gy, gz);
        usleep(100000);
    }
    return 0;
}
