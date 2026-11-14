#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "HTU21DF.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define HTU21DF_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HTU21DF_ADDR);
    HTU21DFFull sensor(connection);                                     // Create HTU21D-F driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t raw_t = 0, raw_rh = 0;
    uint8_t reg = 0, res = 0;

    sensor.init();                                                      // Soft reset, () → void
                                                                        // 15 ms power-up wait, soft reset, 15 ms
    sensor.read(t, rh);                                                 // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                        // no hold master, CRC checked, one retry on a mismatch
    printk("%.2f C, %.1f %%RH\n", (double)(t), (double)(rh));
    printk("humidity %.1f %%RH\n", (double)(sensor.read_humidity()));
    // sensor.read_humidity() → float %RH (NAN on failure), not clamped
    printk("temperature %.2f C\n", (double)(sensor.read_temperature()));
    // sensor.read_temperature() → float °C (NAN on failure)
    printk("compensated humidity %.1f %%RH\n", (double)(sensor.read_humidity_compensated()));
    // sensor.read_humidity_compensated() → float %RH, -0.15 %RH/°C relative to 25 °C (NAN on failure)
    sensor.read_raw(raw_t, raw_rh);                                     // Read raw codes, (temperature_raw, humidity_raw) → bool
                                                                        // 16-bit codes, status bits cleared
    printk("raw T %u raw RH %u\n", (unsigned)raw_t, (unsigned)raw_rh);

    sensor.set_resolution(3);                                           // Set resolution, (res) → bool
                                                                        // 0 = 12/14 bit RH/T, 1 = 8/12, 2 = 10/13, 3 = 11/11
    res = sensor.get_resolution();                                      // Get resolution, () → uint8_t
    printk("resolution setting %u\n", (unsigned)res);
    sensor.set_resolution(0);                                           // Back to 12/14 bit

    sensor.set_heater(true);                                            // Switch the heater, (on) → void
                                                                        // about 5.5 mW; readings drift while on
    printk("heater on %u\n", (unsigned)(sensor.heater()));
    // sensor.heater() → bool
    sensor.set_heater(false);                                           // Heater off
    printk("battery low %u\n", (unsigned)(sensor.battery_low()));
    // sensor.battery_low() → bool, true when VDD is below about 2.25 V
    reg = sensor.read_user_register();                          // Read the user register, () → uint8_t
                                                                        // 0x02 after reset
    printk("user register 0x%02X\n", (unsigned)reg);
    sensor.set_hold_master(false);                                      // Use hold-master commands, (on) → void
                                                                        // needs bus-layer clock stretching; off polls the NACKed read header
    printk("dew point %.2f C\n", (double)(sensor.dew_point()));
    // sensor.dew_point() → float °C, one measurement (NAN on failure)
    sensor.soft_reset();                                                // Soft reset, () → void
                                                                        // 0xFE, waits 15 ms; the heater bit survives
    printk("done\n");
    while (true) k_sleep(K_MSEC(1000));
    return 0;
}
