#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "As7262.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x49;
    I2CConnectionLinux connection(bus, addr);

    AS7262Minimal sensor(connection);                               // Create AS7262 driver, (connection) → void
    AS7262Channels ch = {};
    if (!sensor.begin()) {                                          // Reset and configure defaults, () → bool
        printf("AS7262 not found\n");
    }

    while (true) {
        bool ok = sensor.read(ch);                                  // Read six calibrated channels, (ch) → bool
        printf("V %.1f B %.1f G %.1f Y %.1f O %.1f R %.1f (ok=%lu)\n", (double)(ch.violet), (double)(ch.blue), (double)(ch.green), (double)(ch.yellow), (double)(ch.orange), (double)(ch.red), (unsigned long)(ok));
        printf("%.0f C\n", (double)(sensor.temperature()));
        usleep(2000 * 1000);
    }
    return 0;
}
