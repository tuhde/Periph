#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCA9536.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x41;
    I2CConnectionLinux connection(bus, addr);

    PCA9536Minimal chip(connection);                        // Create PCA9536 driver, (connection, addr=0x41)
    auto p2 = chip.pin(2);                                      // Get P2 proxy, (n) → IOExpanderPin
    auto p0 = chip.pin(0);                                      // Get P0 proxy, (n) → IOExpanderPin

    p0.mode(OUTPUT);                                            // Set pin direction, (m INPUT/OUTPUT) → bool
    p0.high();                                                  // Drive P0 high, () → void
    p2.mode(INPUT);                                             // Set pin direction, (m INPUT/OUTPUT) → bool
    uint8_t val = p2.read();                                    // Read pin level, () → uint8_t 0|1

    chip.write_port(0, 0x09);                                   // Write the output latch, (port=0, mask) → void
    uint8_t port_val = chip.read_port();                        // Read all 4 pins, (port=0) → uint8_t bitmask

    printf("PORT=0x%X  P2=%d\n", port_val, val);
    return 0;
}
