// Demo Zephyr example for the APDS-9930 — adaptive backlight + screen-lock.

#include <stdio.h>
#include <zephyr/kernel.h>
#include "Apds9930.h"
#include "I2CConnectionZephyr.h"

#define DIM_LUX_THRESHOLD 10.0f
#define PROX_SCREEN_OFF  400

int main() {
    I2CConnectionZephyr connection(0x39);                                       // Create I2C connection, (addr=0x39) → I2CConnectionZephyr
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