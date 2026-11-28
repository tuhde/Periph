#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include <math.h>
#include "TSL2561.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    if (!device_is_ready(i2c_dev)) {
        printk("FAIL device_ready\n");
        return 1;
    }
    I2CConnectionZephyr connection(i2c_dev, TSL2561Minimal::I2C_ADDRESS);
    TSL2561Minimal sensor(connection);                     // Create TSL2561 driver, (connection)
    check_true(sensor.init(), "init");                     // Power up, 1× gain, 402 ms, () → bool
    float lx = sensor.readLux();                           // Read illuminance, () → float lux
    check_true(lx >= 0.0f && lx <= 40000.0f, "read_lux_in_range");

    TSL2561Full full(connection);                          // Create TSL2561 Full driver, (connection)
    full.init();
    int id = full.readId();                                // Read the ID register, () → int
    check_true(id >= 0 && (id >> 4) == 1, "read_id_partno");
    check_true(full.isPowered(), "is_powered");            // Power state, () → bool
    uint16_t ch0 = 0, ch1 = 0;
    check_true(full.readRaw(ch0, ch1), "read_raw");        // Read both channels, (ch0&, ch1&) → bool
    full.setGain(16);                                      // Set ADC gain, (gain 1|16) → bool
    full.setIntegrationTime(101);                          // Set integration time, (ms 14|101|402) → bool
    check_true(full.getGain() == 16 && full.getIntegrationTime() == 101, "gain_integration_roundtrip");
    full.setGain(1);
    full.setIntegrationTime(402);
    lx = full.autoRange();                                 // Auto-range and read lux, () → float lux
    check_true(lx >= 0.0f, "auto_range_lux");
    full.setThresholds(100, 30000);                        // Set channel-0 window, (low, high) → bool
    uint16_t lo = 0, hi = 0;
    full.getThresholds(lo, hi);                            // Read the window, (low&, high&) → bool
    check_true(lo == 100 && hi == 30000, "thresholds_roundtrip");
    check_true(full.configureInterrupt(0, 0), "configure_interrupt");  // Interrupt mode, (mode, persist) → bool
    check_true(full.clearInterrupt(), "clear_interrupt");  // Release a level interrupt, () → bool
    full.pollInterrupt();                                  // Check ch0 against the window, () → bool
    full.powerDown();                                      // Power down, () → bool
    check_true(!full.isPowered(), "power_down");
    full.powerUp();                                        // Power up, () → bool
    check_true(full.isPowered(), "power_up");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
