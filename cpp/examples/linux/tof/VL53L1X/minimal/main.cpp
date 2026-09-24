#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "VL53L1X.h"

int main() {
    I2CConnectionLinux connection(1, VL53L1XMinimal::I2C_ADDRESS);
    VL53L1XMinimal sensor(connection);                      // Create VL53L1X driver, (connection)

    while (1) {
        uint16_t d = sensor.distance();                     // Measure distance, () → uint16_t mm
        if (sensor.rangeValid()) {                          // Check last measurement, () → bool
            printf("%u mm\n", (unsigned)d);
        } else {
            printf("out of range\n");
        }
        usleep(200UL * 1000UL);
    }
    return 0;
}
