#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "MCP4725.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x60;
    I2CTransportLinux transport(bus, addr);

    MCP4725Minimal dac(transport);                                         // Create MCP4725 driver, (transport)

    while (true) {
        for (uint16_t v = 0; v <= 4095; v += 256) {
            dac.set_voltage(v);                                            // Set DAC output, (value 0–4095) → void
            printf("dac=%u\n", v);
            usleep(100000);
        }
    }
    return 0;
}
