#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MCP23017.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x20;
    I2CConnectionLinux connection(bus, addr);

    MCP23017Full mcp(connection);                                           // Create MCP23017 driver, (connection, addr=0x20)

    // --- LED chaser on port B, button on GPA0 ---
    // Cycles a single LED across the port B outputs and pauses while the
    // active-low button (internal pull-up) is held.
    auto button = mcp.pin(0);
    button.mode(INPUT_PULLUP);                                             // Set pin direction, (m INPUT/OUTPUT/INPUT_PULLUP) → void
    for (uint8_t n = 8; n < 16; n++)
        mcp.pin(n).mode(OUTPUT);
    uint8_t pos = 0;
    while (true) {
        if (button.read() == LOW) { usleep(10000); continue; }            // Read pin level, () → uint8_t HIGH/LOW
        mcp.write_port(1, (uint8_t)(1 << pos));                            // Write LED pattern, (port 0/1, mask) → void
        pos = (pos + 1) & 7;
        usleep(100000);
    }
    return 0;
}
