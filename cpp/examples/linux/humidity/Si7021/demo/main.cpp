#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Si7021.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x40;
    I2CConnectionLinux connection(bus, addr);

    Si7021Full sensor(connection);                                      // Create Si7021 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw = 0;
    uint8_t rh_bits = 0, t_bits = 0;

    sensor.software_reset();                                            // Software reset, () → void
    printf("device type 0x%02X\n", (unsigned)sensor.device_type());
    uint64_t sn = sensor.serial_number();                               // Electronic serial number, () → uint64_t
    printf("serial number 0x%08lX%08lX\n", (unsigned long)(sn >> 32), (unsigned long)(sn & 0xFFFFFFFFUL));
    printf("firmware 0x%02X\n", (unsigned)sensor.firmware_revision());
    // sensor.device_type() / sensor.firmware_revision() → uint8_t
    printf("VDD low %u\n", (unsigned)sensor.vdd_low());

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for (uint8_t i = 0; i < 15; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        printf("%.2f C %.1f %%RH dew point %.2f C\n", t, rh, sensor.dew_point());
        // sensor.dew_point() → float °C
        usleep(2000 * 1000);
    }

    // --- Value accessors and raw codes ---
    printf("RH %.1f T %.2f T of last RH %.2f\n", sensor.read_humidity(), sensor.read_temperature(), sensor.read_temperature_previous());
    sensor.read_raw_humidity(raw);                                      // Read raw RH code, (code) → bool
    printf("raw RH %u\n", (unsigned)raw);
    sensor.set_clock_stretching(false);                                 // Use hold-master commands, (on) → void
    uint8_t reg = sensor.read_user_register();                          // Read USER_REG_1, () → uint8_t
    sensor.write_user_register(reg);                                    // Write USER_REG_1, (value) → void

    // --- Resolution comparison ---
    static const uint8_t PAIRS[4][2] = {{12, 14}, {10, 13}, {11, 11}, {8, 12}};
    for (uint8_t i = 0; i < 4; i++) {
        sensor.set_resolution(PAIRS[i][0], PAIRS[i][1]);                // Set resolution, (rh_bits, temperature_bits) → bool
        uint32_t t0 = (uint32_t)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
        sensor.read(t, rh);
        uint32_t dt = (uint32_t)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count() - t0;
        sensor.get_resolution(rh_bits, t_bits);
        printf("%u/%u bit: %.2f C %.1f %%RH, %lu ms (typ. %.1f ms)\n", (unsigned)rh_bits, (unsigned)t_bits, t, rh, (unsigned long)dt, sensor.conversion_time() * 1000.0f);
    }
    sensor.set_resolution(12, 14);

    // --- Heater test: 10 s ---
    float rh_before = sensor.read_humidity();
    float t_before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    usleep(10000 * 1000);
    printf("heater on %u\n", (unsigned)sensor.is_heater_on());
    printf("RH drop %.1f %%RH, temperature rise %.2f K\n", rh_before - sensor.read_humidity(), sensor.read_temperature() - t_before);
    sensor.set_heater(false);
    return 0;
}
