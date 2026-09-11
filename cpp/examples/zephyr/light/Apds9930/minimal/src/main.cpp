// Minimal Zephyr example for the APDS-9930.

#include <stdio.h>
#include <zephyr/kernel.h>
#include "Apds9930.h"
#include "I2CConnectionZephyr.h"

int main() {
    I2CConnectionZephyr connection(0x39);                                       // Create I2C connection, (addr=0x39) → I2CConnectionZephyr
    APDS9930Minimal apds(connection);                                          // Create APDS-9930 Minimal, (connection) → APDS9930Minimal

    k_msleep(110);
    while (true) {
        float lx = apds.lux();                                                  // Read ambient illuminance, () → float lx
                                                                               // IR-compensated lux via Ch0/Ch1 difference
        uint16_t p = apds.proximity();                                          // Read proximity count, () → uint16_t count
                                                                               // 16-bit ADC value; higher = closer object
        printf("lux=%.1f lx  proximity=%u\n", lx, p);
        k_msleep(1000);
    }
    return 0;
}