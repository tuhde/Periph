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

    // --- Light meter ---
    // Reads the illuminance once in each one-time mode, prints continuous H-mode
    // values ten times per second for 5 s (cover and uncover the sensor), shows the
    // effect of the lowest and highest sensitivity on the raw count and the
    // measurement time, and finishes with reset and a power cycle.
    float lux = 0;
    uint16_t raw = 0;
    sensor.measure_once(BH1750Full::MODE_H, lux);                   // One-time measurement, (mode, lux&) → bool
    printk("H  %.2f lx\n", (double)(lux));
    sensor.measure_once(BH1750Full::MODE_H2, lux);
    printk("H2 %.2f lx\n", (double)(lux));
    sensor.measure_once(BH1750Full::MODE_L, lux);
    printk("L  %.2f lx\n", (double)(lux));
    sensor.start_continuous(BH1750Full::MODE_H);                    // Start continuous mode, (mode) → bool
    for (int i = 0; i < 50; i++) {
        k_sleep(K_MSEC(100));
        sensor.read_continuous(lux);                                // Read the latest result, (lux&) → bool
        printk("%.1f lx\n", (double)(lux));
    }
    sensor.set_sensitivity(0.45f);                                  // Set sensitivity, (factor) → bool
    sensor.measure_once(BH1750Full::MODE_H, lux);
    sensor.read_raw(raw);
    printk("x0.45: mtreg %lu raw %lu max %.0f ms\n", (unsigned long)(sensor.get_measurement_time_register()), (unsigned long)(raw), (double)(sensor.measurement_time() * 1000.0f));
    sensor.set_sensitivity(3.68f);
    sensor.measure_once(BH1750Full::MODE_H, lux);
    sensor.read_raw(raw);
    printk("x3.68: mtreg %lu raw %lu max %.0f ms\n", (unsigned long)(sensor.get_measurement_time_register()), (unsigned long)(raw), (double)(sensor.measurement_time() * 1000.0f));
    sensor.set_sensitivity(1.0f);
    sensor.reset();                                                 // Clear the data register, () → void
    sensor.power_down();                                            // Power down, () → void
    sensor.power_on();                                              // Power on, () → void
    return 0;
}
