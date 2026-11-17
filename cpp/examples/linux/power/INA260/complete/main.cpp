#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "INA260.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);

    INA260Full ina(connection);                                             // Create INA260 driver, (connection)

    printf("mfr=0x%04X die=0x%04X\n",
           ina.manufacturer_id(), ina.die_id());                           // Read manufacturer/die ID, () → uint16_t
    printf("V=%.4f I=%.4f P=%.4f\n",
           ina.voltage(), ina.current(), ina.power());
    printf("ready=%d ovf=%d\n",
           ina.conversion_ready(), ina.overflow());                        // Check flags, () → bool
    ina.configure(3, 4, 4, 7);                                            // Configure ADC averages+times+mode, (avg, vbus_ct, ish_ct, mode) → void
    ina.set_alert(INA260Full::POL, 1.0f, false, true);                    // Set alert threshold, (func, limit, inv, latch) → void
    ina.clear_alert();                                                      // Disable alerts, () → void
    ina.trigger(INA260Full::MODE_SHUNT_BUS_TRIGGERED);                    // One triggered conversion, (mode) → void
    printf("alert=0x%04X\n", ina.alert_flags());                          // Read alert flags, () → uint16_t
    ina.shutdown();
    usleep(1000);
    ina.wake();
    ina.reset();                                                           // Trigger software reset, () → void
    return 0;
}
