#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include <math.h>
#include "MAX44009.h"

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
    I2CConnectionZephyr connection(i2c_dev, MAX44009Minimal::I2C_ADDRESS);
    MAX44009Minimal sensor(connection);                    // Create MAX44009 driver, (connection)
    float lx = sensor.lux();                                // Read illuminance, () → float lux
    check_true(lx >= 0.0f && lx <= 188006.4f, "lux_in_range");

    MAX44009Full full(connection);                          // Create MAX44009 Full driver, (connection)
    uint8_t e = 0, m = 0;
    bool over = full.readRaw(e, m);                         // Read exponent and mantissa, (exponent&, mantissa&) → bool overrange
    check_true(e <= 15 && over == (e == 15), "read_raw_ranges");
    full.setContinuous(true);                               // Select measurement mode, (continuous bool) → void
    check_true(full.getConfig().continuous, "continuous_on"); // Read CONFIG, () → Config
    full.setContinuous(false);
    check_true(!full.getConfig().continuous, "continuous_off");
    full.setManual(100.0f, false);                          // Select manual range, (integrationMs, divideBy8=false) → void
    MAX44009Full::Config cfg = full.getConfig();
    check_true(cfg.manual && fabsf(cfg.integrationMs - 100.0f) < 0.01f && !cfg.divideBy8, "manual_100ms");
    full.setManual(25.0f, true);
    cfg = full.getConfig();
    check_true(fabsf(cfg.integrationMs - 25.0f) < 0.01f && cfg.divideBy8, "manual_25ms_divide");
    full.setAutomatic();                                    // Select automatic range, () → void
    check_true(!full.getConfig().manual, "automatic");
    full.setThresholds(50.0f, 500.0f);                      // Set the interrupt window, (lowLux, highLux) → bool
    float lo = 0, hi = 0;
    full.getThresholds(lo, hi);                             // Read the window, (lowLux&, highLux&) → void
    check_true(fabsf(lo - 50.0f) / 50.0f < 0.2f && fabsf(hi - 500.0f) / 500.0f < 0.2f, "thresholds_roundtrip");
    full.setThresholdDelay(2.0f);                           // Set the threshold delay, (seconds) → void
    check_true(fabsf(full.getThresholdDelay() - 2.0f) < 0.05f, "threshold_delay");
    full.enableInterrupt();                                 // Enable the interrupt, () → void
    full.disableInterrupt();                                // Disable the interrupt, () → void
    check_true(full.pollInterrupt() == 0, "poll_interrupt_cleared"); // Read and clear INT_STATUS, () → uint8_t

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
