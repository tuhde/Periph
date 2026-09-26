// Demo Zephyr example for the MPR121 — 12-button musical keyboard.

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

static const char* NOTES[12] = {
    "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4"
};

int main() {
    const struct device* i2c_dev = DEVICE_DT_GET(MPR121_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MPR121_ADDR);                       // Create I2C connection, (dev, addr=0x5A) → I2CConnectionZephyr
    MPR121Full mpr(connection);                                                 // Create MPR121 Full, (connection) → MPR121Full
    uint16_t previous = 0;

    while (true) {
        uint16_t mask = mpr.touched();                                          // Read 12-bit touch bitmask, () → uint16_t bitmask
        uint16_t newly_pressed = mask & ~previous;
        uint16_t newly_released = (~mask) & previous;
        for (uint8_t n = 0; n < 12; n++) {
            if (newly_pressed & (1u << n)) {
                printf("NOTE ON:  %s\n", NOTES[n]);
            }
            if (newly_released & (1u << n)) {
                printf("NOTE OFF: %s\n", NOTES[n]);
            }
        }
        previous = mask;
        k_msleep(50);
    }
    return 0;
}
