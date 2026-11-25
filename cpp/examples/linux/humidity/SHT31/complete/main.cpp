#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "SHT31.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x44;
    I2CConnectionLinux connection(bus, addr);

    SHT31Full sensor(connection);                                       // Create SHT31 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0, status = 0;

    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // single shot, high repeatability, CRC checked
    printf("%.2f C, %.1f %%RH\n", t, rh);
    printf("temperature %.2f C\n", sensor.read_temperature());
    // sensor.read_temperature() → float °C (NAN on failure)
    printf("humidity %.1f %%RH\n", sensor.read_humidity());
    // sensor.read_humidity() → float %RH (NAN on failure)
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw words, (raw_t, raw_rh) → bool
                                                                        // 16-bit temperature and humidity ticks
    printf("raw %u %u\n", (unsigned)raw_t, (unsigned)raw_rh);

    sensor.set_repeatability(SHT31Full::REPEATABILITY_LOW);             // Select repeatability, (level) → bool
                                                                        // low / medium / high (4 / 6 / 15 ms)
    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool
    sensor.set_repeatability(SHT31Full::REPEATABILITY_HIGH);            // Select repeatability, (level) → bool
    sensor.set_clock_stretching(false);                                 // Use clock-stretching commands, (on) → void
                                                                        // needs bus-layer support; off polls the NACKed read header

    sensor.start_periodic(2.0f, SHT31Full::REPEATABILITY_HIGH);         // Start periodic mode, (rate, repeatability=HIGH, art=false) → bool
                                                                        // rate 0.5 / 1 / 2 / 4 / 10 mps; art=true selects the 4 mps ART mode
    usleep(1000 * 1000);
    bool have = sensor.fetch(t, rh);                                    // Fetch the latest periodic result, (t, rh) → bool
                                                                        // false while the read header is NACKed
    printf("fetch %u: %.2f C %.1f %%RH\n", (unsigned)have, t, rh);
    sensor.stop_periodic();                                             // Stop periodic mode, () → void
                                                                        // break command 0x3093, waits 1 ms

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // about 5-25 mW; readings are unusable while on
    sensor.read_status(status);                                         // Read status register, (status) → bool
                                                                        // CRC verified; bit 13 = heater on
    printf("status 0x%04X\n", (unsigned)status);
    sensor.set_heater(false);                                           // Switch the heater, (on) → void
    sensor.clear_status();                                              // Clear status flags, () → void
                                                                        // command 0x3041
    printf("dew point %.2f C\n", sensor.dew_point());
    // sensor.dew_point() → float °C, one measurement, Magnus formula (NAN on failure)
    sensor.soft_reset();                                                // Soft reset, () → void
                                                                        // 0x30A2, waits 1 ms, back to single shot
    printf("done\n");
    return 0;
}
