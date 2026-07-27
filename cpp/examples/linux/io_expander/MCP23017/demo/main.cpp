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

    MCP23017Full mcp(connection);                                           // Create MCP23017 driver, (connection)

    // --- LED chaser on port B, button on port A pin 0 ---
    // Cycles a single LED across port B outputs; stops while button is held.
    mcp.set_direction(0, 0xFF);                                            // Set port A as inputs, (port=0, mask) → void
    mcp.set_pullup(0, 0xFF);                                               // Enable pull-ups port A, (port=0, mask) → void
    mcp.set_direction(1, 0x00);                                            // Set port B as outputs, (port=1, mask) → void
    uint8_t pos = 0;
    while (true) {
        if (!mcp.read_pin(0, 0)) { usleep(10000); continue; }             // Read button (active-low), (port=0, pin=0) → bool
        mcp.write_port(1, (uint8_t)(1 << pos));                           // Write LED pattern, (port=1, value) → void
        pos = (pos + 1) & 7;
        usleep(100000);
    }
    return 0;
}
