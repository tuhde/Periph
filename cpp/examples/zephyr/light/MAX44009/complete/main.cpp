#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "MAX44009.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MAX44009Minimal::I2C_ADDRESS);
    MAX44009Full sensor(connection);                    // Create MAX44009 Full driver, (connection)
    float lx = sensor.lux();                            // Read illuminance, () → float lux
    // 2^E × M × 0.045; 188006.4 on overrange
    printk("lux %.2f\n", (double)lx);
    uint8_t e = 0, m = 0;
    bool over = sensor.readRaw(e, m);                   // Read exponent and mantissa, (exponent&, mantissa&) → bool overrange
    // one consistent sample: LUX_HIGH, LUX_LOW, LUX_HIGH
    printk("E=%d M=%d overrange=%d\n", (int)e, (int)m, (int)over);
    bool last = sensor.isOverrange();                   // Query the last reading, () → bool
    // true if the last exponent field was 15
    float res = sensor.resolution();                    // Lux per LSB, () → float lux
    // 2^E × 0.045 for the last reading
    printk("overrange %d resolution %.3f\n", (int)last, (double)res);
    sensor.setContinuous(true);                         // Select measurement mode, (continuous bool) → void
    // true: a new measurement as soon as one finishes
    sensor.setManual(100.0f, false);                    // Select manual range, (integrationMs, divideBy8=false) → void
    // nearest of 800/400/200/100/50/25/12.5/6.25 ms
    MAX44009Full::Config cfg = sensor.getConfig();      // Read CONFIG, () → Config
    // {continuous, manual, divideBy8, integrationMs}
    printk("manual %d integration %.2f ms\n", (int)cfg.manual, (double)cfg.integrationMs);
    sensor.setAutomatic();                              // Select automatic range, () → void
    // the chip chooses the current divider and integration time
    sensor.setContinuous(false);                        // Back to one measurement per 800 ms, (continuous bool) → void
    bool ok = sensor.setThresholds(50.0f, 500.0f);      // Set the interrupt window, (lowLux, highLux) → bool
    // 4 mantissa MSBs: about 1/16 to 1/8 of the value
    float lo = 0, hi = 0;
    sensor.getThresholds(lo, hi);                       // Read the window, (lowLux&, highLux&) → void
    printk("window %.2f .. %.2f lx (set %d)\n", (double)lo, (double)hi, (int)ok);
    sensor.setThresholdDelay(2.0f);                     // Set the threshold delay, (seconds) → void
    // 0–25.5 s in 0.1 s steps
    float delay = sensor.getThresholdDelay();           // Read the delay, () → float s
    printk("delay %.1f s\n", (double)delay);
    sensor.enableInterrupt();                           // Enable the interrupt, () → void
    // INT_ENABLE = 1
    sensor.onInterrupt([](uint8_t status) { (void)status; });  // Subscribe, (callback(status), intPin=nullptr) → void
    // falling edge on INT; call service() periodically without a pin
    sensor.service();                                   // Poll once and dispatch the callback, () → void
    uint8_t status = sensor.pollInterrupt();            // Read and clear INT_STATUS, () → uint8_t
    // bit 0 = SOURCE_THRESHOLD
    printk("status %d\n", (int)status);
    sensor.offInterrupt();                              // Unsubscribe, () → void
    sensor.disableInterrupt();                          // Disable the interrupt, () → void
    // INT_ENABLE = 0; also clears a pending status
    return 0;
}
