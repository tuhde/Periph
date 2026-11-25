#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <unistd.h>
#include <stddef.h>
#include "I2CConnectionLinux.h"
#include "AXP2101.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x34;
    I2CConnectionLinux connection(bus, addr);

    AXP2101Minimal chip(connection);                    // Create AXP2101 driver, (connection)

    while (true) {
        float v = chip.batteryVoltage();                // Read battery voltage, () → float V
        uint8_t pct = chip.batteryPercent();            // Read state of charge, () → uint8_t %
        uint8_t state = chip.chargeState();             // Read charge state, () → uint8_t (0 trickle … 5 none)
        bool vbus = chip.vbusPresent();                 // Check input source, () → bool
        printf("%.3f V  %d %%  state=%d  vbus=%s\n", (double)v, (int)pct, (int)state, (vbus) ? "true" : "false");
        usleep(1000000);
    }
    return 0;
}
