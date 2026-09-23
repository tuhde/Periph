#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "MCP9808.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static volatile uint8_t pendingStatus = 0;
static volatile bool pending = false;

static void onAlert(uint8_t status) {
    pendingStatus = status;
    pending = true;
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MCP9808Minimal::I2C_ADDRESS);
    MCP9808Full sensor(connection);                         // Create MCP9808 Full driver, (connection)
                                                            // checks MANUFACTURER_ID 0x0054 and DEVICE_ID 0x04

    float t = sensor.readTemperature();                     // Read ambient temperature, () → °C
                                                            // masks TA's 3 status bits, decodes 1/16 °C two's complement
    printk("temperature %.4f C\n", (double)t);

    sensor.setResolution(0.25f);                            // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → bool
                                                            // 0.25 °C step converts in ~65 ms instead of 250 ms
    printk("resolution %.4f C\n", (double)sensor.getResolution());  // Read resolution, () → °C
                                                            // decodes the RESOLUTION register code

    sensor.shutdown();                                      // Enter Shutdown mode, () → void
                                                            // stops conversion; TA keeps its last value
    printk("shutdown %d\n", sensor.isShutdown());              // Check Shutdown mode, () → bool
                                                            // reads CONFIG.SHDN
    sensor.wake();                                          // Leave Shutdown mode, () → void
                                                            // resumes continuous conversion
    k_msleep(100);

    sensor.setUpperLimit(30.0f);                            // Set TUPPER, (celsius °C) → void
                                                            // rounded to the nearest 0.25 °C step
    sensor.setLowerLimit(10.0f);                            // Set TLOWER, (celsius °C) → void
                                                            // rounded to the nearest 0.25 °C step
    sensor.setCriticalLimit(45.0f);                         // Set TCRIT, (celsius °C) → void
                                                            // rounded to the nearest 0.25 °C step
    printk("upper %.2f C\n", (double)sensor.getUpperLimit());  // Read TUPPER, () → °C
                                                            // decodes the 0.25 °C two's-complement boundary
    printk("lower %.2f C\n", (double)sensor.getLowerLimit());  // Read TLOWER, () → °C
                                                            // decodes the 0.25 °C two's-complement boundary
    printk("critical %.2f C\n", (double)sensor.getCriticalLimit());  // Read TCRIT, () → °C
                                                            // decodes the 0.25 °C two's-complement boundary

    sensor.setHysteresis(1.5f);                             // Set hysteresis, (celsius 0|1.5|3.0|6.0) → bool
                                                            // applied on the cooling edge of each boundary only
    printk("hysteresis %.1f C\n", (double)sensor.getHysteresis());  // Read hysteresis, () → °C
                                                            // decodes CONFIG.THYST

    // sensor.lockCriticalLimit();                          // Lock TCRIT, () → void
    //                                                      // irreversible until power-on reset
    // sensor.lockWindowLimits();                           // Lock TUPPER/TLOWER, () → void
    //                                                      // irreversible until power-on reset
    printk("crit locked %d\n", sensor.isCriticalLimitLocked());  // Check TCRIT lock, () → bool
                                                            // reads CONFIG.CRIT_LOCK
    printk("win locked %d\n", sensor.isWindowLimitsLocked());  // Check TUPPER/TLOWER lock, () → bool
                                                            // reads CONFIG.WIN_LOCK

    sensor.configureAlert(MCP9808Full::AlertMode::All,
                          MCP9808Full::AlertOutput::Interrupt,
                          MCP9808Full::AlertPolarity::ActiveLow);  // Configure Alert, (mode=All, output=Comparator, polarity=ActiveLow) → bool
                                                            // sets ALERT_SEL, ALERT_MOD and ALERT_POL together
    sensor.enableAlert();                                   // Enable Alert output, () → void
                                                            // sets CONFIG.ALERT_CNT
    printk("alert asserted %d\n", sensor.isAlertAsserted());  // Check Alert output, () → bool
                                                            // reads the read-only CONFIG.ALERT_STAT

    uint8_t status = sensor.pollInterrupt();                // Read boundary status, () → uint8_t mask
                                                            // TA's live bits: SOURCE_LOWER/UPPER/CRITICAL, nothing cleared
    printk("below lower %d above upper %d critical %d\n",
        (status & MCP9808Full::SOURCE_LOWER) != 0,
        (status & MCP9808Full::SOURCE_UPPER) != 0,
        (status & MCP9808Full::SOURCE_CRITICAL) != 0);
    sensor.clearInterrupt();                                // Clear interrupt-mode Alert, () → void
                                                            // writes CONFIG.INT_CLEAR=1; no effect in comparator mode

    sensor.onInterrupt(onAlert);                            // Subscribe to Alert, (callback, intPin=nullptr) → void
                                                            // callback receives the pollInterrupt() mask; needs an InputPin
    k_msleep(5000);
    if (pending) printk("alert, status mask %u\n", (unsigned)pendingStatus);
    sensor.offInterrupt();                                  // Unsubscribe, () → void
                                                            // detaches the edge handler
    sensor.disableAlert();                                  // Disable Alert output, () → void
                                                            // clears CONFIG.ALERT_CNT
    return 0;
}
