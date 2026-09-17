#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Mpr121.h"

static const char* NOTES[12] = {
    "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4"
};

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x5A;
    I2CConnectionLinux connection(bus, addr);                                 // Create I2C connection, (bus, addr) → I2CConnectionLinux

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
        usleep(50 * 1000);
    }
}
