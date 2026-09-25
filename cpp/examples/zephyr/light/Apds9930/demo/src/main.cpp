// Demo Zephyr example for the APDS-9930 — adaptive backlight + screen-lock.

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

#define DIM_LUX_THRESHOLD 10.0f
#define PROX_SCREEN_OFF  400

int main() {
    const struct device* i2c_dev = DEVICE_DT_GET(APDS9930_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, APDS9930_ADDR);                                       // Create I2C connection, (addr=0x39) → I2CConnectionZephyr
    APDS9930Full apds(connection);                                              // Create APDS-9930 Full, (connection) → APDS9930Full

    k_msleep(110);
    // --- Sample lux and proximity once per second for 30 cycles ---
    for (int i = 0; i < 30; i++) {
        k_msleep(1000);
        float lx = apds.lux();                                                  // Read ambient illuminance, () → float lx
        uint16_t p = apds.proximity();                                          // Read proximity count, () → uint16_t count
        printf("lux=%.1f lx  proximity=%u\n", lx, p);
        if (lx < DIM_LUX_THRESHOLD) {
            printf("  -> dim backlight\n");
        }
        if (p > PROX_SCREEN_OFF) {
            printf("  -> disable screen\n");
        }
    }
    return 0;
}