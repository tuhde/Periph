#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCF8575.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x20;
    I2CConnectionLinux connection(bus, addr);

    PCF8575Full pcf(connection);                                            // Create PCF8575 driver, (connection)

    printf("port=0x%04X\n", pcf.read());                                  // Read all 16 pins, () → uint16_t
    pcf.write(0xFFFF);                                                     // Write all 16 pins, (value) → void
    pcf.write_pin(8, false);                                               // Drive single pin low, (pin=0–15, value) → void
    printf("pin8=%d\n", pcf.read_pin(8));                                 // Read single pin, (pin=0–15) → bool
    pcf.toggle(0x00FF);                                                    // Toggle masked pins, (mask) → void
    return 0;
}
