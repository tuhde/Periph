#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "MCP4XXX.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x58;
    I2CConnectionLinux connection(bus, addr);

    MCP4XXXMinimal pot(connection, MCP4XXXPart::MCP4532, 10000.0f, 75.0f); // Create MCP4XXX driver, (connection, part, r_ab=10000 Ω, r_wiper=75 Ω)
    pot.init();                                              // Bus check, () → void

    pot.set_wiper(32, 0);                                    // Set wiper, (code 0-128, channel=0) → void
    pot.set_resistance(2200.0f, 0);                          // Set W-B resistance, (ohms, channel=0) → void

    while (true) {
        uint16_t code = pot.get_wiper(0);                        // Read wiper, (channel=0) → uint16_t 0-128
        float ohms = pot.get_resistance(0);                      // Read W-B resistance, (channel=0) → float Ω
        printf("code=%d R_WB ohm=%.1f\n", (int)(code), (double)(ohms));
        usleep((1000) * 1000);
    }
    return 0;
}
