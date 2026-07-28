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

    INA219Full ina(connection);                                             // Create INA219 driver, (connection)

    // --- USB charger monitor ---
    // Logs bus voltage, current, and power every second.
    // Alert printed when current exceeds 0.5 A.
    ina.calibrate(0.1f, 2.0f);                                            // Set shunt+max-current for calibration, (r_shunt Ω, max_A) → void
    while (true) {
        float v = ina.voltage(), i = ina.current(), p = ina.power();      // Read bus voltage/current/power, () → float
        printf("%.3f V  %.4f A  %.4f W%s\n",
               v, i, p, i > 0.5f ? "  [HIGH CURRENT]" : "");
        usleep(1000000);
    }
    return 0;
}
