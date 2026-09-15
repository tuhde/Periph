// Minimal Zephyr example for the MPR121.

#include <stdio.h>
#include <zephyr/kernel.h>
#include "Mpr121.h"
#include "I2CConnectionZephyr.h"

int main() {
    I2CConnectionZephyr connection(0x5A);                                       // Create I2C connection, (addr=0x5A) → I2CConnectionZephyr
    MPR121Minimal mpr(connection);                                             // Create MPR121 Minimal, (connection) → MPR121Minimal

    while (true) {
        uint16_t t = mpr.touched();                                            // Read 12-bit touch bitmask, () → uint16_t bitmask
                                                                               // bit n=1 means ELEn is currently touched
        printf("touched=0x%03X\n", t);
        k_msleep(1000);
    }
    return 0;
}
