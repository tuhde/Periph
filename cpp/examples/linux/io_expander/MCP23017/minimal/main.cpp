#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "MCP23017.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x20;
    I2CTransportLinux transport(bus, addr);

    MCP23017Minimal mcp(transport);                                        // Create MCP23017 driver, (transport)

    mcp.set_direction(0x00, 0xFF);                                         // Set pin direction, (port=0/1, mask 0=out 1=in) → void
    while (true) {
        uint8_t val = mcp.read_port(0);                                    // Read port A, (port=0) → uint8_t
        printf("GPIOA=0x%02X\n", val);
        mcp.write_port(1, 0xAA);                                           // Write port B, (port=1, value) → void
        usleep(500000);
    }
    return 0;
}
