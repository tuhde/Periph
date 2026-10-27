#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "LSM303.h"

int main() {
    const char* bus_env = getenv("I2C_BUS");
    int bus = bus_env ? atoi(bus_env) : 1;
    I2CConnectionLinux accel_connection(bus, 0x19);
    I2CConnectionLinux mag_connection(bus, 0x1E);

    LSM303Minimal sensor(accel_connection, mag_connection);  // Create LSM303 driver, (accel_connection, mag_connection)

    while (true) {
        float ax, ay, az;
        sensor.read_acceleration(ax, ay, az);                // Read 3-axis acceleration, (x, y, z) → g, g, g
        float mx, my, mz;
        sensor.read_magnetic(mx, my, mz);                    // Read 3-axis magnetic field, (x, y, z) → µT, µT, µT
        printf("a=(%.3f %.3f %.3f) g  m=(%.1f %.1f %.1f) uT\n",
               (double)ax, (double)ay, (double)az, (double)mx, (double)my, (double)mz);
        usleep(100000);
    }
    return 0;
}
