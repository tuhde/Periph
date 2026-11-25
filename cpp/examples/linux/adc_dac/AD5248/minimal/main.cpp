#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "AD5248.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x2C;
    I2CConnectionLinux connection(bus, addr);

    AD5248Minimal pot(connection, 10000.0f, 160.0f);             // Create AD5248 driver, (connection, r_ab=10000 Ω, r_wiper=160 Ω)

    pot.set_wiper(0, 64);                                    // Set channel 0 wiper, (channel 0-1, code 0-255) → void
    pot.set_resistance(1, 2200.0f);                          // Set channel 1 W-B resistance, (channel 0-1, ohms) → void

    while (true) {
        uint8_t code = pot.get_wiper(0);                     // Read channel 0 wiper, (channel 0-1) → uint8_t 0-255
        float ohms = pot.get_resistance(0);                  // Read channel 0 W-B resistance, (channel 0-1) → float Ω
        printf("ch0 code=%d R_WB ohm=%.1f\n", (int)(code), (double)(ohms));
        usleep(1000000);
    }
    return 0;
}
