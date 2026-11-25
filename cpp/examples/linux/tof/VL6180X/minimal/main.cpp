#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "VL6180X.h"

int main() {
    I2CConnectionLinux connection(1, VL6180XMinimal::I2C_ADDRESS, nullptr, nullptr, 2);
    VL6180XMinimal sensor(connection);                      // Create VL6180X driver, (connection)
    sensor.init();                                          // Initialize, () → bool

    while (1) {
        uint8_t mm = 0;
        if (sensor.read_range(mm)) {                        // Single-shot range, (uint8_t& mm) → bool
            printf("%u mm\n", (unsigned)mm);
        } else {
            printf("range error %u\n", (unsigned)sensor.last_error()); // Last range error code, () → uint8_t
        }
        usleep(200UL * 1000UL);
    }
    return 0;
}
