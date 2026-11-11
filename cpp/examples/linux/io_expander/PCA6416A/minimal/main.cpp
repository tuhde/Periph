#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "PCA6416A.h"

static PCA6416AMinimal* g_chip = nullptr;

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x20;
    I2CConnectionLinux connection(bus, addr);

    PCA6416AMinimal chip(connection);                         // Create PCA6416A driver, (connection, addr=0x20)
    g_chip = &chip;

    auto p0 = chip.pin(0);                                    // Get P0_0 proxy, (n) → IOExpanderPin
    auto p7 = chip.pin(15);                                    // Get P1_7 proxy, (n) → IOExpanderPin

    p7.mode(OUTPUT);                                          // Set pin direction, (m INPUT/OUTPUT) → bool
    p7.high();                                                // Drive P1_7 high, () → void
    p0.mode(INPUT);                                           // Set pin direction, (m INPUT/OUTPUT) → bool
    uint8_t val = p0.read();                                  // Read pin level, () → uint8_t 0|1

    chip.write_port(0, 0x81);                                 // Write port 0 output latch, (port, mask) → void
    uint8_t port_val = chip.read_port(1);                      // Read the 8 pins of port 1, (port) → uint8_t bitmask

    printf("PORT1=0x%02X  P0=%d\n", port_val, val);
    return 0;
}
