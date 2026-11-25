#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "HDC1080.h"

#define I2C_NODE   DT_NODELABEL(i2c0)
#define HDC1080_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HDC1080_ADDR);
    HDC1080Full sensor(connection);                                     // Create HDC1080 driver, (connection) → void
    float t = 0, rh = 0;

    uint64_t serial = 0;
    sensor.init();                                                      // Write default configuration, () → void
    serial = sensor.serial_id();                                        // Read the 40-bit serial ID, () → uint64_t
    printk("serial id 0x%02X%08X\n", (unsigned)((serial) >> 32), (unsigned)((serial) & 0xFFFFFFFF));

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for (uint8_t i = 0; i < 15; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        float g = logf(fmaxf(rh, 0.01f) / 100.0f) + 17.62f * t / (243.12f + t);
        float dew = 243.12f * g / (17.62f - g);                         // Magnus dew point, °C
        printk("%.2f C %.1f %%RH dew point %.2f C\n", (double)(t), (double)(rh), (double)(dew));
        k_sleep(K_MSEC(2000));
    }

    // --- Heater test: 10 s ---
    float rh0 = sensor.read_humidity();
    float t0c = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    k_sleep(K_MSEC(10000));
    float rh1 = sensor.read_humidity();
    float t1c = sensor.read_temperature();
    printk("RH drop %.1f %%RH, temperature rise %.2f K\n", (double)(rh0 - rh1), (double)(t1c - t0c));
    sensor.set_heater(false);                                           // Heater off

    // --- Resolution comparison ---
    sensor.set_resolution(11, 8);                                       // Set resolution, (temperature_bits, humidity_bits) → bool
    uint32_t ts = k_uptime_get_32();
    sensor.read(t, rh);
    uint32_t dt = k_uptime_get_32() - ts;
    printk("11/8 bit: %.2f C %.1f %%RH in %u ms\n", (double)(t), (double)(rh), (unsigned)(dt));
    sensor.set_resolution(14, 14);                                      // Back to 14/14 bit
    printk("battery low %u\n", (unsigned)(sensor.battery_low()));
    // sensor.battery_low() → bool, true when VDD is below 2.8 V
    while (true) k_sleep(K_MSEC(1000));
    return 0;
}
