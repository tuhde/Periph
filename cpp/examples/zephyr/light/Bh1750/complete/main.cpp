#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Bh1750.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define BH1750_ADDR 0x23

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, BH1750_ADDR);
    BH1750Full sensor(connection);                                  // Create BH1750 driver, (connection) → void
    sensor.init();                                                  // Power on, () → void
                                                                    // opcode 0x01; the chip powers down by itself after each one-time measurement
    float lux = 0;
    uint16_t raw = 0;
    bool ok = sensor.read(lux);                                     // Measure illuminance, (lux&) → bool
                                                                    // one-time H-resolution (1 lx), waits 180 ms
    printk("illuminance %.1f lx\n", (double)(lux));
    ok = sensor.read_raw(raw);                                      // Read the raw count, (raw&) → bool
                                                                    // 16-bit, one one-time measurement in the current mode
    printk("raw %lu\n", (unsigned long)(raw));
    ok = sensor.measure_once(BH1750Full::MODE_H2, lux);             // One-time measurement, (mode, lux&) → bool
                                                                    // MODE_H 1 lx, MODE_H2 0.5 lx, MODE_L 4 lx and 24 ms
    printk("H2 %.2f lx\n", (double)(lux));
    printk("measurement time %.0f ms\n", (double)(sensor.measurement_time() * 1000.0f)); // Maximum measurement time, (mode=MODE_H) → float s
    printk("lux per count %.3f\n", (double)(sensor.lux_per_count())); // Lux per raw count, (mode=MODE_H) → float
    ok = sensor.start_continuous(BH1750Full::MODE_H);               // Start continuous mode, (mode=MODE_H) → bool
                                                                    // first result after measurement_time()
    k_sleep(K_MSEC(200));
    ok = sensor.read_continuous(lux);                               // Read the latest result, (lux&) → bool
                                                                    // no wait
    printk("continuous %.1f lx\n", (double)(lux));
    ok = sensor.set_measurement_time(138);                          // Set MTreg, (mtreg 31-254) → bool
                                                                    // default 69; larger = more sensitive and slower
    printk("mtreg %lu\n", (unsigned long)(sensor.get_measurement_time_register())); // Get MTreg, () → uint8_t
                                                                    // cached, the chip has no read-back
    ok = sensor.set_sensitivity(1.0f);                              // Set sensitivity, (factor 0.45-3.68) → bool
                                                                    // MTreg = round(69 x factor)
    sensor.power_down();                                            // Power down, () → void
    sensor.power_on();                                              // Power on, () → void
    sensor.reset();                                                 // Clear the data register, () → void
                                                                    // powers on first; MTreg and mode are kept
    printk("ok %lu\n", (unsigned long)(ok));
    return 0;
}
