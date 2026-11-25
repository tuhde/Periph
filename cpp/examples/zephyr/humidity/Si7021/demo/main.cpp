#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "Si7021.h"

#define I2C_NODE   DT_NODELABEL(i2c0)
#define Si7021_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, Si7021_ADDR);
    Si7021Full sensor(connection);                                      // Create Si7021 driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw = 0;
    uint8_t rh_bits = 0, t_bits = 0;

    sensor.software_reset();                                            // Software reset, () → void
    printk("device type 0x%02X\n", (unsigned)sensor.device_type());
    uint64_t sn = sensor.serial_number();                               // Electronic serial number, () → uint64_t
    printk("serial number 0x%08lX%08lX\n", (unsigned long)(sn >> 32), (unsigned long)(sn & 0xFFFFFFFFUL));
    printk("firmware 0x%02X\n", (unsigned)sensor.firmware_revision());
    // sensor.device_type() / sensor.firmware_revision() → uint8_t
    printk("VDD low %u\n", (unsigned)sensor.vdd_low());

    // --- Condensation-aware hygrometer: 15 readings, 2 s apart ---
    for (uint8_t i = 0; i < 15; i++) {
        sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool
        printk("%.2f C %.1f %%RH dew point %.2f C\n", (double)(t), (double)(rh), (double)(sensor.dew_point()));
        // sensor.dew_point() → float °C
        k_sleep(K_MSEC(2000));
    }

    // --- Value accessors and raw codes ---
    printk("RH %.1f T %.2f T of last RH %.2f\n", (double)(sensor.read_humidity()), (double)(sensor.read_temperature()), (double)(sensor.read_temperature_previous()));
    sensor.read_raw_humidity(raw);                                      // Read raw RH code, (code) → bool
    printk("raw RH %u\n", (unsigned)raw);
    sensor.set_clock_stretching(false);                                 // Use hold-master commands, (on) → void
    uint8_t reg = sensor.read_user_register();                          // Read USER_REG_1, () → uint8_t
    sensor.write_user_register(reg);                                    // Write USER_REG_1, (value) → void

    // --- Resolution comparison ---
    static const uint8_t PAIRS[4][2] = {{12, 14}, {10, 13}, {11, 11}, {8, 12}};
    for (uint8_t i = 0; i < 4; i++) {
        sensor.set_resolution(PAIRS[i][0], PAIRS[i][1]);                // Set resolution, (rh_bits, temperature_bits) → bool
        uint32_t t0 = k_uptime_get_32();
        sensor.read(t, rh);
        uint32_t dt = k_uptime_get_32() - t0;
        sensor.get_resolution(rh_bits, t_bits);
        printk("%u/%u bit: %.2f C %.1f %%RH, %lu ms (typ. %.1f ms)\n", (unsigned)rh_bits, (unsigned)t_bits, (double)(t), (double)(rh), (unsigned long)dt, (double)(sensor.conversion_time() * 1000.0f));
    }
    sensor.set_resolution(12, 14);

    // --- Heater test: 10 s ---
    float rh_before = sensor.read_humidity();
    float t_before = sensor.read_temperature();
    sensor.set_heater(true);                                            // Switch the heater, (on) → void
    k_sleep(K_MSEC(10000));
    printk("heater on %u\n", (unsigned)sensor.is_heater_on());
    printk("RH drop %.1f %%RH, temperature rise %.2f K\n", (double)(rh_before - sensor.read_humidity()), (double)(sensor.read_temperature() - t_before));
    sensor.set_heater(false);
    while (true) k_sleep(K_MSEC(1000));
    return 0;
}
