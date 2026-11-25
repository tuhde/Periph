#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "AHT10.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x38;
    I2CConnectionLinux connection(bus, addr);

    AHT10Full aht(connection);                                              // Create AHT10 driver, (connection, addr=0x38) → void

    float t, h;
    aht.read(t, h);                                                        // Trigger measurement, (temperature_c, humidity_pct) → void
    printf("t=%.2f h=%.2f\n", t, h);
    printf("calibrated=%d busy=%d status=0x%02X\n",
           aht.is_calibrated(),                                            // Check calibration status, () → bool
           aht.is_busy(),                                                  // Check busy status, () → bool
           aht.read_status());                                             // Read status byte, () → uint8_t
    printf("temperature=%.2f humidity=%.2f\n",
           aht.read_temperature(),                                         // Read temperature only, () → float °C
           aht.read_humidity());                                           // Read humidity only, () → float %RH
    uint32_t raw_rh, raw_t;
    aht.read_raw(raw_rh, raw_t);                                           // Read raw 20-bit values, (raw_humidity, raw_temperature) → bool
    printf("raw rh=%u t=%u dew=%.2f\n", raw_rh, raw_t,
           aht.dew_point());                                               // Dew point, () → float °C
                                                                           // one fresh measurement, Magnus formula
    aht.soft_reset();                                                      // Soft-reset sensor, () → void
                                                                           // sends 0xBA, waits 20 ms, re-runs the init command
    aht.read(t, h);
    printf("after reset t=%.2f h=%.2f\n", t, h);
    return 0;
}
