#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "TSL2561.h"

int main() {
    I2CConnectionLinux connection(1, TSL2561Minimal::I2C_ADDRESS);
    TSL2561Minimal sensor(connection);                  // Create TSL2561 driver, (connection)
    sensor.init();                                      // Power up, 1× gain, 402 ms, wait one integration, () → bool
    for (int i = 0; i < 10; i++) {
        float lx = sensor.readLux();                    // Read illuminance, () → float lux
        printf("lux %.1f lx\n", (double)lx);
        usleep(1000UL * 1000UL);
    }
    return 0;
}
