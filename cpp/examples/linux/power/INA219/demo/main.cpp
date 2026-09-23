#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "INA219.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);

    INA219Full ina(connection, 0.1f, 2.0f);                                 // Create INA219 driver, (connection, r_shunt=0.1 Ω, max_current=2.0 A)

    // --- USB charger monitor ---
    // Logs bus voltage, current, and power every second; the 0.1 Ω shunt
    // and 2 A ceiling set the calibration. Flags currents above 0.5 A.
    while (true) {
        float v = ina.voltage();                                           // Bus voltage, () → float V
        float i = ina.current();                                           // Current, () → float A
        float p = ina.power();                                             // Power, () → float W
        printf("%.3f V  %.4f A  %.4f W%s\n",
               (double)v, (double)i, (double)p, i > 0.5f ? "  [HIGH CURRENT]" : "");
        usleep(1000000);
    }
    return 0;
}
