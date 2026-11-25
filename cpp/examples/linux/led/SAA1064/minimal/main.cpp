#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "SAA1064.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x38;
    I2CConnectionLinux connection(bus, addr);

    SAA1064Minimal display(connection);                             // Create SAA1064 driver, (connection)
    uint16_t n = 0;
    for (int i = 0; i < 100; i++) {
        display.show_number(n);                                     // Show a right-aligned number, (value 0–9999) → bool
        n++;
        usleep(100 * 1000);
    }

    return 0;
}
