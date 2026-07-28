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

    printf("V=%.3f I=%.4f P=%.4f\n",
           ina.voltage(), ina.current(), ina.power());
    printf("shunt=%.6f V\n", ina.shunt_voltage());                        // Read shunt voltage, () → float V
    ina.configure(3, 3, 3, 7, 0);                                         // Configure ADC, (badc, sadc, brng, pga, mode) → void
    printf("ready=%d\n", ina.conversion_ready());                         // Check conversion-ready flag, () → bool
    ina.calibrate(0.1f, 2.0f);                                            // Set shunt+max-current for calibration, (r_shunt Ω, max_A) → void
    ina.reset();                                                           // Trigger power-on reset, () → void
    return 0;
}
