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
    // Demonstrates digit cycling with 100 ms update rate.
    pcf.set_display(true);                                                 // Enable display, (on=true) → void
    char buf[5];
    for (int n = 9999; n >= 0; n--) {
        snprintf(buf, sizeof(buf), "%04d", n);
        pcf.write_digits(buf);                                             // Write digit string, (str) → void
        usleep(100000);
    }
    pcf.clear();                                                           // Clear display, () → void
    return 0;
}
