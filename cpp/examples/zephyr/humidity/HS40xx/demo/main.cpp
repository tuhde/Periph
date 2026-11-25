#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "HS40xx.h"

#define I2C_NODE   DT_NODELABEL(i2c0)
#define HS40xx_ADDR 0x54

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HS40xx_ADDR);
    HS40xxFull sensor(connection);                                  // Create HS40xx driver, (connection) → void
    float t = 0, rh = 0;
    uint8_t st = 0;
    uint32_t id = 0;
    // --- Humidity watchdog ---
    // Single readings for 10 s, then periodic mode with an RH-high alert (breathe on the sensor).
    sensor.read_id(id);                                             // Read the 32-bit sensor ID, (id) → bool
    printk("ID 0x%lX\n", (unsigned long)(id));
    for (uint8_t n = 0; n < 5; n++) {
        sensor.read(t, rh);                                         // Read temperature and humidity, (t, rh) → bool °C, %RH
        printk("%.2f C, %.1f %%RH\n", (double)(t), (double)(rh));
        k_sleep(K_MSEC(2000));
    }
    sensor.set_alert_thresholds(60.0f, 55.0f);                      // Set alert thresholds, (rh_high, rh_high_reset, ...) → bool
    sensor.enable_interrupt(HS40xxFull::SOURCE_RH_HIGH);            // Enable alert sources, (source) → bool
    sensor.start_periodic(1.0f);                                    // Start periodic mode, (period_s) → bool
    for (uint8_t n = 0; n < 15; n++) {
        k_sleep(K_MSEC(1000));
        sensor.read_periodic(t, rh, st);                            // Fetch the latest periodic result, (t, rh, status) → bool
        printk("%.2f C, %.1f %%RH %s\n", (double)(t), (double)(rh), (st & HS40xxFull::SOURCE_RH_HIGH) ? "ALERT" : "ok");
    }
    sensor.stop_periodic();                                         // Stop periodic mode, () → bool
    sensor.soft_reset();                                            // Soft reset, () → bool
    while (true) {
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
