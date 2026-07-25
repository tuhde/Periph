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

    MCP23017Full mcp(transport);                                           // Create MCP23017 driver, (transport)

    mcp.set_direction(0, 0xFF);                                            // Set pin direction port A, (port=0, mask) → void
    mcp.set_direction(1, 0x00);                                            // Set pin direction port B all outputs, (port=1, mask) → void
    mcp.set_pullup(0, 0xFF);                                               // Enable pull-ups port A, (port=0, mask) → void
    mcp.set_polarity(0, 0x00);                                             // Set input polarity port A, (port=0, mask 1=invert) → void
    mcp.configure(0x00);                                                   // Write IOCON register, (value) → void
    printf("GPIOA=0x%02X\n", mcp.read_port(0));                          // Read port A, (port=0) → uint8_t
    mcp.write_port(1, 0xAA);                                               // Write port B, (port=1, value) → void
    mcp.write_pin(1, 3, true);                                             // Set individual pin, (port=1, pin=0–7, value) → void
    printf("pin B3=%d\n", mcp.read_pin(1, 3));                            // Read individual pin, (port=1, pin=0–7) → bool
    return 0;
}
