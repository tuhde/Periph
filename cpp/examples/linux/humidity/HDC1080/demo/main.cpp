#include <chrono>
#include <cstdio>
#include <math.h>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "HDC1080.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);

    HDC1080Full sensor(connection);                                     // Create HDC1080 driver, (connection) → void
    float t = 0, rh = 0;

    uint64_t serial = 0;
    sensor.init();                                                      // Write default configuration, () → void
    serial = sensor.serial_id();                                        // Read the 40-bit serial ID, () → uint64_t
    printf("serial id 0x%02X%08X\n", (unsigned)((serial) >> 32), (unsigned)((serial) & 0xFFFFFFFF));

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for (uint8_t i = 0; i < 15; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        float g = logf(fmaxf(rh, 0.01f) / 100.0f) + 17.62f * t / (243.12f + t);
        float dew = 243.12f * g / (17.62f - g);                         // Magnus dew point, °C
        printf("%.2f C %.1f %%RH dew point %.2f C\n", t, rh, dew);
        usleep(2000 * 1000);
    }

    // --- Heater test: 10 s ---
    float rh0 = sensor.read_humidity();
    float t0c = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    usleep(10000 * 1000);
    float rh1 = sensor.read_humidity();
    float t1c = sensor.read_temperature();
    printf("RH drop %.1f %%RH, temperature rise %.2f K\n", rh0 - rh1, t1c - t0c);
    sensor.set_heater(false);                                           // Heater off

    // --- Resolution comparison ---
    sensor.set_resolution(11, 8);                                       // Set resolution, (temperature_bits, humidity_bits) → bool
    uint32_t ts = (uint32_t)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    sensor.read(t, rh);
    uint32_t dt = (uint32_t)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count() - ts;
    printf("11/8 bit: %.2f C %.1f %%RH in %u ms\n", t, rh, (unsigned)(dt));
    sensor.set_resolution(14, 14);                                      // Back to 14/14 bit
    printf("battery low %u\n", (unsigned)(sensor.battery_low()));
    // sensor.battery_low() → bool, true when VDD is below 2.8 V
    return 0;
}
