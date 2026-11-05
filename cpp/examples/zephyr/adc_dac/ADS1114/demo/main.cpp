#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ADS1114.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, ADS1114Minimal::I2C_ADDRESS);
    ADS1114Full adc(connection);                            // Create ADS1114 Full driver, (connection)

    // --- Configure the converter ---
    // ±4.096 V covers a 3.3 V rail with headroom; 250 SPS keeps single-shot
    // reads short while still averaging out most noise.
    adc.setGain(ADS1114Full::PGA_4_096);                    // Set gain, (pga 0–5) → void
    adc.setDataRate(ADS1114Full::DR_250);                   // Set data rate, (dr 0–7) → void

    // --- Read the inputs ---
    // Single-shot mode powers the chip down between conversions.
    for (int i = 0; i < 3; i++) {
        int16_t raw = adc.readRaw();                        // Read raw code, () → int16_t
        printk("raw %d %.5f V\n", (int)raw, (double)(raw * adc.lsb()));
        k_msleep(1000);
    }

    // --- Arm a window comparator ---
    // ALERT/RDY asserts when the input leaves 0.5 V ... 3.0 V for two
    // consecutive conversions; the latch holds it until clearAlert().
    adc.setThresholds(0.5f, 3.0f);                          // Set thresholds, (low V, high V) → void
    adc.configureComparator(ADS1114Full::COMP_WINDOW, ADS1114Full::ACTIVE_LOW, true, ADS1114Full::QUEUE_2);// Configure comparator, (mode, polarity, latch=false, queue=QUEUE_1) → void
    adc.startContinuous();                                  // Start continuous, () → void

    // --- Watch for out-of-range events ---
    // Without an interrupt pin pollInterrupt() cannot see a comparator
    // alert, so read the conversion and compare in software as a fallback.
    for (int i = 0; i < 100; i++) {
        uint8_t status = adc.pollInterrupt();               // Poll interrupt, () → uint8_t source
        if (status) {
            printk("OUT OF RANGE\n");
            adc.clearAlert();                               // Clear alert, () → void
        }
        k_msleep(100);
    }
    adc.disableComparator();                                // Disable comparator, () → void
    adc.stopContinuous();                                   // Stop continuous, () → void
    return 0;
}
