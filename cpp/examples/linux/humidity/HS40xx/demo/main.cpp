#include <chrono>
#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "HS40xx.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x54;
    I2CConnectionLinux connection(bus, addr);

    HS40xxFull sensor(connection);                                  // Create HS40xx driver, (connection) → void
    float t = 0, rh = 0;
    uint8_t st = 0;
    uint32_t id = 0;
    // --- Humidity watchdog ---
    // Single readings for 10 s, then periodic mode with an RH-high alert (breathe on the sensor).
    sensor.read_id(id);                                             // Read the 32-bit sensor ID, (id) → bool
    printf("ID 0x%lX\n", (unsigned long)(id));
    for (uint8_t n = 0; n < 5; n++) {
        sensor.read(t, rh);                                         // Read temperature and humidity, (t, rh) → bool °C, %RH
        printf("%.2f C, %.1f %%RH\n", t, rh);
        usleep(2000 * 1000);
    }
    sensor.set_alert_thresholds(60.0f, 55.0f);                      // Set alert thresholds, (rh_high, rh_high_reset, ...) → bool
    sensor.enable_interrupt(HS40xxFull::SOURCE_RH_HIGH);            // Enable alert sources, (source) → bool
    sensor.start_periodic(1.0f);                                    // Start periodic mode, (period_s) → bool
    for (uint8_t n = 0; n < 15; n++) {
        usleep(1000 * 1000);
        sensor.read_periodic(t, rh, st);                            // Fetch the latest periodic result, (t, rh, status) → bool
        printf("%.2f C, %.1f %%RH %s\n", t, rh, (st & HS40xxFull::SOURCE_RH_HIGH) ? "ALERT" : "ok");
    }
    sensor.stop_periodic();                                         // Stop periodic mode, () → bool
    sensor.soft_reset();                                            // Soft reset, () → bool
    return 0;
}
