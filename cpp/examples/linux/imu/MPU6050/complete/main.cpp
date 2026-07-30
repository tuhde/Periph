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

    uint8_t id = mpu.who_am_i();                                           // Read WHO_AM_I register, () → uint8_t  (0x68)
    printf("who_am_i=0x%02X\n", id);
    mpu.configure_accel(2);                                                // Set accelerometer full-scale, (full_scale 0-3 → ±2/4/8/16 g) → void
    mpu.configure_gyro(0);                                                 // Set gyroscope full-scale, (full_scale 0-3 → ±250/500/1000/2000 °/s) → void
    mpu.configure_dlpf(3);                                                 // Set DLPF bandwidth, (dlpf 0–6) → void
    float ax, ay, az, gx, gy, gz;
    mpu.accel(ax, ay, az);                                                 // Read 3-axis acceleration, (x, y, z) → m/s²
    mpu.gyro(gx, gy, gz);                                                  // Read 3-axis angular rate, (x, y, z) → rad/s
    float temp = mpu.temperature();                                        // Read die temperature, () → float °C
    printf("a=%.3f,%.3f,%.3f  g=%.3f,%.3f,%.3f  t=%.2f\n",
           ax, ay, az, gx, gy, gz, temp);
    mpu.set_sleep(true);                                                   // Enter sleep mode, (sleep=true) → void
    usleep(1000);
    mpu.set_sleep(false);                                                  // Wake from sleep, (sleep=false) → void
    return 0;
}
