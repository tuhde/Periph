#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Is31fl3731.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x74;
    I2CConnectionLinux connection(bus, addr);

    Is31fl3731Minimal matrix(connection);                 // Create IS31FL3731 driver, (connection)

    for (uint8_t col = 0; col < Is31fl3731Minimal::COLS; col++) {
        matrix.set_pixel(0, col, 128);                    // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → bool
    }
    usleep((1000) * 1000);
    matrix.fill(32);                                      // Set every LED's PWM brightness, (brightness 0–255) → void
    usleep((1000) * 1000);
    matrix.clear();                                       // Switch all LEDs off, () → void

    return 0;
}
