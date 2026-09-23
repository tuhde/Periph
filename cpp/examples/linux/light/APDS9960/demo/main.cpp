#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "APDS9960.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x39;
    I2CConnectionLinux connection(bus, addr);

    APDS9960Full apds(connection);                                          // Create APDS9960 driver, (connection)

    // --- Colour temperature logger ---
    // Reads RGBC 2×/s and estimates correlated colour temperature (CCT).
    // McCamy's approximation: CCT = 449n³ + 3525n² + 6823.3n + 5520.33
    // where n = (r - b) / g.
    apds.configure_als(0xB6, 1);                                           // Set ALS timing and gain, (atime 0–255, again 0–3) → void
    while (true) {
        uint16_t c, r, g, b;
        apds.color(c, r, g, b);                                            // Read all four channels, (clear, red, green, blue) → void
        if (g > 0) {
            float n = ((float)r - (float)b) / (float)g;
            float cct = 449*n*n*n + 3525*n*n + 6823.3f*n + 5520.33f;
            printf("CCT=%.0f K  c=%u\n", (double)cct, c);
        }
        usleep(500000);
    }
    return 0;
}
