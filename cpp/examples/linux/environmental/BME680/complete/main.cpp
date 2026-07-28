#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "BME680.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x76;
    I2CConnectionLinux connection(bus, addr);

    BME680Full bme(connection);                                             // Create BME680 driver, (connection)

    uint8_t id = bme.chip_id();                                            // Read chip ID, () → uint8_t  (0x61 for BME680)
    printf("chip_id=0x%02X\n", id);
    bme.configure(2, 5, 1, 4, 320, 150);                                  // Configure oversampling+filter+heater, (osrs_h, osrs_t, osrs_p, filter, heater_temp_c, heater_dur_ms) → void
    float t, h, p, gas;
    bme.read(t, h, p, gas);                                                // Trigger forced-mode measurement, (temp_c, humidity_pct, pressure_pa, gas_ohm) → void
    printf("t=%.2f h=%.2f p=%.2f gas=%.0f\n", t, h, p, gas);
    bme.reset();                                                           // Trigger soft reset, () → void
    return 0;
}
