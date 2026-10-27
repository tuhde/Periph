#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "LSM303.h"

int main() {
    const char* bus_env = getenv("I2C_BUS");
    int bus = bus_env ? atoi(bus_env) : 1;

    I2CConnectionLinux accelConn(bus, 0x19);                // Accelerometer connection at 0x19
    I2CConnectionLinux magConn(bus, 0x1E);                  // Magnetometer connection at 0x1E

    LSM303Minimal chip(accelConn, magConn);                 // Create LSM303 driver, (accel_conn, mag_conn) → sets ±2 g, 100 Hz, ±130 µT, 15 Hz

    while (true) {
        float ax, ay, az, mx, my, mz;
        chip.readAcceleration(ax, ay, az);                  // Read acceleration, (x, y, z) → g, g, g
        chip.readMagnetic(mx, my, mz);                      // Read magnetic field, (x, y, z) → µT, µT, µT
        printf("a=%.3f,%.3f,%.3f g  m=%.1f,%.1f,%.1f uT\n",
               (double)ax, (double)ay, (double)az,
               (double)mx, (double)my, (double)mz);
        usleep(200000);
    }
    return 0;
}
