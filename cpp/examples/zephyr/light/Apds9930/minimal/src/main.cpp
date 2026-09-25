// Minimal Zephyr example for the APDS-9930.

#include <stdio.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "Apds9930.h"
#include "I2CConnectionZephyr.h"

#ifndef APDS9930_I2C_NODE
#define APDS9930_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef APDS9930_ADDR
#define APDS9930_ADDR 0x39
#endif

int main() {
    const struct device* i2c_dev = DEVICE_DT_GET(APDS9930_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, APDS9930_ADDR);                                       // Create I2C connection, (addr=0x39) → I2CConnectionZephyr
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