#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MCP4728.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x60;
    I2CConnectionLinux connection(bus, addr);

    MCP4728Minimal dac(connection);                                         // Create MCP4728 driver, (connection)

    // Set all four channels to mid-scale
    for (int ch = 0; ch < 4; ch++)
        dac.set_voltage(ch, 2048);                                         // Set channel output, (ch=0–3, value 0–4095) → void
    printf("set all channels to 2048\n");
    return 0;
}
