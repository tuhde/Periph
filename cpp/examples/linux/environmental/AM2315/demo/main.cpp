#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <math.h>
#include "I2CConnectionLinux.h"
#include "AM2315.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x5C;
    I2CConnectionLinux connection(bus, addr);

    AM2315Full sensor(connection);                                           // Create AM2315 driver, (connection) → void
    float t = 0, rh = 0, t_min = 1000.0f, t_max = -1000.0f, h_min = 1000.0f, h_max = -1000.0f;
    uint16_t model = 0, user1 = 0, user2 = 0;
    uint8_t version = 0;
    uint32_t device_id = 0;

    sensor.read_device_info(model, version, device_id);                      // Read device information, (model, version, device_id) → bool
    printf("model %u version %u device id 0x%08lX\n", (unsigned)(model), (unsigned)(version), (unsigned long)(device_id));

    // --- Weather-station logger: a sample every 5 s, min/max tracking ---
    // The sensor measures after a communication and returns that result on the next one,
    // so every sample is the previous measurement; the driver enforces the >= 2 s spacing
    // and the CRC-16 check.
    for (uint8_t i = 1; i <= 12; i++) {
        if (i % 10 == 0) sensor.read_fresh(t, rh);                         // Real-time value, (t, rh) → bool, two reads
        else sensor.read(t, rh);                                           // Read temperature and humidity, (t, rh) → bool
        if (t < t_min) t_min = t;
        if (t > t_max) t_max = t;
        if (rh < h_min) h_min = rh;
        if (rh > h_max) h_max = rh;
        float g = logf((rh > 0.1f ? rh : 0.1f) / 100.0f) + 17.62f * t / (243.12f + t);   // Magnus formula
        float dew = 243.12f * g / (17.62f - g);
        printf("#%u  %.1f C  %.1f %%RH  dew point %.1f C  (T %.1f..%.1f, RH %.1f..%.1f)\n", (unsigned)(i), t, rh, dew, t_min, t_max, h_min, h_max);
        usleep(5000 * 1000);
    }

    // --- User registers as a persistent counter ---
    sensor.write_user_registers(12, 0xBEEF);                                 // Write user registers, (user1, user2) → bool
    sensor.read_user_registers(user1, user2);                                // Read user registers, (user1, user2) → bool
    printf("user registers %u 0x%02X\n", (unsigned)(user1), (unsigned)(user2));
    printf("done\n");
    return 0;
}
