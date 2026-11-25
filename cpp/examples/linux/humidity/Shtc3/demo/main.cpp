#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "Shtc3.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x70;
    I2CConnectionLinux connection(bus, addr);

    SHTC3Full sensor(connection);                                   // Create SHTC3 driver, (connection) → void
    sensor.init();                                                  // Soft reset and ID check, () → bool

    // --- Identify the chip ---
    // The ID word carries the SHTC3 product code in bits 11 and 5:0.
    float t = 0, rh = 0;
    uint16_t t_raw = 0, rh_raw = 0, id = 0;
    sensor.read_id(id);                                             // Read the ID word, (id&) → bool
    printf("id %02X\n", (unsigned)(id));

    // --- Battery-friendly climate logger: 6 readings, 10 s apart ---
    // The sensor sleeps (0.3 uA) between the readings; every read wakes it.
    for (int i = 0; i < 6; i++) {
        sensor.read(t, rh);                                         // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
        printf("%.2f C %.1f %%RH\n", (double)(t), (double)(rh));
        usleep(10000 * 1000);
    }

    // --- Compare normal and low-power mode ---
    // Low power measures in 0.7 ms instead of 10.8 ms at slightly higher noise.
    sensor.read(t, rh);
    printf("normal    %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.set_low_power(true);                                     // Select low-power mode, (on) → void
    sensor.read(t, rh);
    printf("low power %.2f C %.1f %%RH\n", (double)(t), (double)(rh));
    sensor.set_low_power(false);

    // --- Dew point and raw words, then finish asleep ---
    printf("dew point %.2f C\n", (double)(sensor.dew_point()));     // Compute dew point, () → float °C
    sensor.read_raw(t_raw, rh_raw);                                 // Read raw codes, (t_raw&, rh_raw&) → bool
    printf("raw T %lu raw RH %lu\n", (unsigned long)(t_raw), (unsigned long)(rh_raw));
    sensor.sleep();                                                 // Sleep command, () → void
    return 0;
}
