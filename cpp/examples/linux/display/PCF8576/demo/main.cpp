#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCF8576.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x38;
    I2CConnectionLinux connection(bus, addr);

    PCF8576Full pcf(connection);                                            // Create PCF8576 driver, (connection)

    // --- Countdown timer from 9999 to 0 ---
    // Demonstrates digit cycling with a 100 ms update rate: each number is
    // split into four digits and written through the 7-segment lookup table.
    pcf.enable();                                                          // Turn the display on, () → void
    for (int n = 9999; n >= 0; n--) {
        int value = n;
        for (int pos = 3; pos >= 0; pos--) {
            pcf.set_digit_7seg(pos, PCF8576Full::SEVEN_SEG[value % 10]);   // Write one 7-segment digit, (position, segments) → void
            value /= 10;
        }
        usleep(100000);
    }
    pcf.clear();                                                           // Clear display RAM, () → void
    return 0;
}
