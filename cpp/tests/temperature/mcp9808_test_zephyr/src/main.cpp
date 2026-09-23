#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "MCP9808.h"

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
    I2CConnectionZephyr connection(i2c_dev, MCP9808Minimal::I2C_ADDRESS);

    MCP9808Minimal sensor(connection);                      // Create MCP9808 driver, (connection)
    float t = sensor.readTemperature();                     // Read ambient temperature, () → °C
    check_true(t >= -40.0f && t <= 125.0f, "temperature_plausible");

    MCP9808Full full(connection);                           // Create MCP9808 Full driver, (connection)
    full.setResolution(0.5f);                               // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → bool
    check_true(full.getResolution() == 0.5f, "resolution_0_5");
    full.setResolution(0.0625f);                            // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → bool
    check_true(full.getResolution() == 0.0625f, "resolution_0_0625");

    full.setUpperLimit(80.0f);                              // Set TUPPER, (celsius °C) → void
    check_true(full.getUpperLimit() == 80.0f, "upper_limit_roundtrip");
    full.setLowerLimit(-10.25f);                            // Set TLOWER, (celsius °C) → void
    check_true(full.getLowerLimit() == -10.25f, "lower_limit_roundtrip");
    full.setCriticalLimit(100.0f);                          // Set TCRIT, (celsius °C) → void
    check_true(full.getCriticalLimit() == 100.0f, "critical_limit_roundtrip");

    full.setHysteresis(1.5f);                               // Set hysteresis, (celsius 0|1.5|3.0|6.0) → bool
    check_true(full.getHysteresis() == 1.5f, "hysteresis_roundtrip");
    full.setHysteresis(0.0f);                               // Set hysteresis, (celsius 0|1.5|3.0|6.0) → bool

    full.shutdown();                                        // Enter Shutdown mode, () → void
    check_true(full.isShutdown(), "shutdown");
    full.wake();                                            // Leave Shutdown mode, () → void
    check_true(!full.isShutdown(), "wake");

    // Lower limit above ambient forces TA < TLOWER; the status bit is live
    // regardless of whether the Alert output is enabled.
    full.setLowerLimit(t + 20.0f);                          // Set TLOWER, (celsius °C) → void
    k_msleep(300);
    check_true((full.pollInterrupt() & MCP9808Full::SOURCE_LOWER) != 0, "poll_interrupt_lower");
    full.setLowerLimit(-10.25f);                            // Set TLOWER, (celsius °C) → void
    k_msleep(300);
    check_true((full.pollInterrupt() & MCP9808Full::SOURCE_LOWER) == 0, "poll_interrupt_clear");

    check_true(full.configureAlert(), "configure_alert");   // Configure Alert, (mode=All, output=Comparator, polarity=ActiveLow) → bool
    full.enableAlert();                                     // Enable Alert output, () → void
    check_true(!full.isAlertAsserted(), "alert_not_asserted_in_window");
    full.disableAlert();                                    // Disable Alert output, () → void
    full.clearInterrupt();                                  // Clear interrupt-mode Alert, () → void

    // The lock bits are one-way until power-on reset and are never set here.
    check_true(!full.isCriticalLimitLocked(), "not_critical_locked");
    check_true(!full.isWindowLimitsLocked(), "not_window_locked");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
