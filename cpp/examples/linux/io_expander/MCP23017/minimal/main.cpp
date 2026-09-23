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

    MCP23017Minimal mcp(connection);                                        // Create MCP23017 driver, (connection, addr=0x20)
                                                                           // all 16 pins start as inputs

    for (uint8_t n = 8; n < 16; n++)
        mcp.pin(n).mode(OUTPUT);                                           // Set pin direction, (m INPUT/OUTPUT) → void
                                                                           // pins 8–15 = port B
    while (true) {
        uint8_t val = mcp.read_port(0);                                    // Read port A, (port 0/1) → uint8_t bitmask
        printf("GPIOA=0x%02X\n", val);
        mcp.write_port(1, 0xAA);                                           // Write port B, (port 0/1, mask) → void
        usleep(500000);
    }
    return 0;
}
