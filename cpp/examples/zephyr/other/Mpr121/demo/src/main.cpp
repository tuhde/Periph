// Demo Zephyr example for the MPR121 — 12-button musical keyboard.

#include <stdio.h>
#include <zephyr/kernel.h>
#include "Mpr121.h"
#include "I2CConnectionZephyr.h"

static const char* NOTES[12] = {
    "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4"
};

int main() {
    I2CConnectionZephyr connection(0x5A);                                       // Create I2C connection, (addr=0x5A) → I2CConnectionZephyr
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
