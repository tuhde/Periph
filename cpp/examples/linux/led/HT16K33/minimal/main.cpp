#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "HT16K33.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x70;
    I2CConnectionLinux connection(bus, addr);

    HT16K33Minimal matrix(connection);                      // Create HT16K33 driver, (connection)

    for (uint8_t row = 0; row < HT16K33Minimal::ROWS; row++) {
        matrix.set_pixel(0, row);                         // Set one LED in the shadow RAM, (com 0–7, row 0–15, on) → bool
    }
    matrix.show();                                        // Write the shadow RAM to the chip, () → void
    usleep((1000) * 1000);

    return 0;
}
