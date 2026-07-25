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

    Mpu6050Full mpu(transport);                                            // Create MPU-6050 driver, (transport)

    uint8_t id = mpu.who_am_i();                                           // Read WHO_AM_I register, () → uint8_t  (0x68)
    printf("who_am_i=0x%02X\n", id);
    mpu.set_accel_range(2);                                                // Set accelerometer full-scale, (range 0-3 → ±2/4/8/16 g) → void
    mpu.set_gyro_range(0);                                                 // Set gyroscope full-scale, (range 0-3 → ±250/500/1000/2000 °/s) → void
    mpu.set_dlpf(3);                                                       // Set DLPF bandwidth, (mode 0–6) → void
    float ax, ay, az, gx, gy, gz;
    mpu.read(ax, ay, az, gx, gy, gz);                                      // Read accel+gyro, (ax,ay,az m/s², gx,gy,gz °/s) → void
    float temp = mpu.temperature();                                        // Read die temperature, () → float °C
    printf("a=%.3f,%.3f,%.3f  g=%.3f,%.3f,%.3f  t=%.2f\n",
           ax, ay, az, gx, gy, gz, temp);
    mpu.sleep(true);                                                       // Enter/exit sleep mode, (sleep=true) → void
    usleep(1000);
    mpu.sleep(false);                                                      // Enter/exit sleep mode, (sleep=false) → void
    return 0;
}
