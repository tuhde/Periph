#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "TSL2561.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, TSL2561Minimal::I2C_ADDRESS);
    TSL2561Full sensor(connection);                     // Create TSL2561 Full driver, (connection)
    bool ok = sensor.init();                            // Power up, 1× gain, 402 ms, wait one integration, () → bool
    // CONTROL ← 0x03 and read back; lastError() = ERR_INIT if it does not read 0x03
    int id = sensor.readId();                           // Read the ID register, () → int
    // bits 7:4 = 1 for the TSL2561, bits 3:0 revision; -1 on a bus error
    printk("init %d id 0x%02X\n", (int)ok, id);
    float lx = sensor.readLux();                        // Read illuminance, () → float lux
    // NAN with lastError() = ERR_SATURATED on a saturated channel
    printk("lux %.1f error %d\n", (double)lx, sensor.lastError());  // Error of the last operation, () → int
    uint16_t ch0 = 0, ch1 = 0;
    sensor.readRaw(ch0, ch1);                           // Read both channels, (ch0&, ch1&) → bool
    // one block read: ch0 visible+IR, ch1 IR
    sensor.readLuxRaw(lx, ch0, ch1);                    // Lux plus counts, (lux&, ch0&, ch1&) → bool
    printk("lux %.1f ch0 %u ch1 %u\n", (double)lx, (unsigned)ch0, (unsigned)ch1);
    float calc = TSL2561Minimal::calculateLux(ch0, ch1, 1, 402, TSL2561Minimal::PACKAGE_T);  // Convert counts, (ch0, ch1, gain, integrationMs, package) → float lux
    printk("calculated %.1f lx\n", (double)calc);

    sensor.setGain(16);                                 // Set ADC gain, (gain 1|16) → bool
    // 16× for dim light; saturates indoors at ~1–2 klx
    sensor.setIntegrationTime(101);                     // Set integration time, (ms 14|101|402) → bool
    // the conversion in progress keeps the old setting
    printk("gain %u integration %u ms\n", (unsigned)sensor.getGain(), (unsigned)sensor.getIntegrationTime());  // Current settings, () → uint8_t, () → uint16_t ms
    k_sleep(K_MSEC(250));
    sensor.setPackage(TSL2561Minimal::PACKAGE_CS);      // Select lux coefficients, (package) → void
    // T, FN and CL share one set; CS has its own
    printk("lux CS %.1f\n", (double)sensor.readLux());
    sensor.setPackage(TSL2561Minimal::PACKAGE_T);
    lx = sensor.autoRange();                            // Auto-range and read lux, () → float lux
    // 16×/402 ms … 1×/13.7 ms until no saturation and ch0 > 100
    printk("auto %.1f lx\n", (double)lx);

    sensor.startManual();                               // Start a host-timed integration, () → bool
    // INTEG = 11, MANUAL = 1
    k_sleep(K_MSEC(200));
    sensor.stopManual();                                // Stop the integration, () → bool
    // INTEG = 11, MANUAL = 0; counts are ready
    sensor.readRaw(ch0, ch1);
    printk("manual ch0 %u ch1 %u\n", (unsigned)ch0, (unsigned)ch1);
    sensor.setIntegrationTime(402);
    sensor.setGain(1);

    sensor.setThresholds(100, 20000);                   // Set channel-0 window, (low, high counts) → bool
    // word writes of 0x2–0x3 and 0x4–0x5
    uint16_t low = 0, high = 0;
    sensor.getThresholds(low, high);                    // Read the window, (low&, high&) → bool
    printk("window %u .. %u\n", (unsigned)low, (unsigned)high);
    sensor.configureInterrupt(1, 2);                    // Interrupt mode, (mode 0|1|2, persist 0–15) → bool
    // level interrupt after 2 cycles outside the window
    bool outside = sensor.pollInterrupt();              // Check ch0 against the window, () → bool
    // does not clear the chip's interrupt
    printk("outside %d\n", (int)outside);
    sensor.clearInterrupt();                            // Release a level interrupt, () → bool
    // command byte 0xC0
    sensor.onInterrupt([](uint16_t c0) { (void)c0; });  // Subscribe, (callback(ch0), intPin=nullptr) → void
    // falling edge on INT; call service() periodically without a pin
    sensor.service();                                   // Poll once and dispatch the callback, () → void
    sensor.offInterrupt();                              // Unsubscribe, () → void
    sensor.configureInterrupt(0, 0);

    bool powered = sensor.isPowered();                  // Power state, () → bool
    // CONTROL reads 0x03 when powered
    sensor.powerDown();                                 // Power down, () → bool
    // CONTROL = 0x00; registers keep their values
    sensor.powerUp();                                   // Power up, () → bool
    // CONTROL = 0x03; integration restarts
    printk("powered %d\n", (int)powered);
    return 0;
}
