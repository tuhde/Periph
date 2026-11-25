#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MCP23008.h"

static MCP23008Minimal* g_chip = nullptr;

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x20;
    I2CConnectionLinux connection(bus, addr);

    MCP23008Minimal chip(connection);                         // Create MCP23008 driver, (connection, addr=0x20)
    g_chip = &chip;
    auto p0 = chip.pin(0);                                    // Get GP0 proxy, (n) → IOExpanderPin
    auto p7 = chip.pin(7);                                    // Get GP7 proxy, (n) → IOExpanderPin

    p7.mode(OUTPUT);                                          // Set pin direction, (m INPUT/OUTPUT) → void
    p7.high();                                                // Drive GP7 high, () → void
    p0.mode(INPUT);                                           // Set pin direction, (m INPUT/OUTPUT) → void
    uint8_t val = p0.read();                                  // Read pin level, () → uint8_t 0|1

    chip.write_port(0, 0x81);                                 // Write the output latch, (port=0, mask) → void
    uint8_t port_val = chip.read_port();                      // Read all 8 pins, (port=0) → uint8_t bitmask

    printf("GPIO=0x%02X  GP0=%d\n", port_val, val);
    return 0;
}
