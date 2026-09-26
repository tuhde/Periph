// Minimal Zephyr example for the MPR121.

#include <stdio.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "Mpr121.h"
#include "I2CConnectionZephyr.h"

#ifndef MPR121_I2C_NODE
#define MPR121_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MPR121_ADDR
#define MPR121_ADDR 0x5A
#endif

int main() {
    const struct device* i2c_dev = DEVICE_DT_GET(MPR121_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MPR121_ADDR);                       // Create I2C connection, (dev, addr=0x5A) → I2CConnectionZephyr
    MPR121Minimal mpr(connection);                                             // Create MPR121 Minimal, (connection) → MPR121Minimal

    while (true) {
        uint16_t t = mpr.touched();                                            // Read 12-bit touch bitmask, () → uint16_t bitmask
                                                                               // bit n=1 means ELEn is currently touched
        printf("touched=0x%03X\n", t);
        k_msleep(1000);
    }
    return 0;
}
