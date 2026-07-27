#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "INA226.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);

    INA226Full ina(connection);                                             // Create INA226 driver, (connection)

    // --- Power rail monitor with over-current alert ---
    // Samples every 500 ms, prints a warning when current exceeds 1 A.
    ina.configure(3, 4, 4, 7);                                            // Configure ADC, (avg=8, 1.1ms, 1.1ms, continuous) → void
    while (true) {
        float v = ina.voltage(), i = ina.current(), p = ina.power();      // Read bus voltage/current/power, () → float
        printf("%.4f V  %.4f A  %.4f W%s\n",
               v, i, p, i > 1.0f ? "  [OC]" : "");
        usleep(500000);
    }
    return 0;
}
