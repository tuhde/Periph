#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ADS1013.h"

#define I2C_NODE DT_NODELABEL(i2c0)

// Battery-voltage logger: the cell is read through a 2:1 resistor divider
// (AIN0, AIN1 to GND).

static const float DIVIDER_RATIO = 2.0f;
static const int LOG_SAMPLES = 10;

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, ADS1013Minimal::I2C_ADDRESS);
    ADS1013Full adc(connection);                        // Create ADS1013 Full driver, (connection)

    // --- Slow, low-noise single-shot logging ---
    // 128 SPS gives the lowest noise; one conversion per second keeps the chip
    // in power-down in between.
    adc.setDataRate(ADS1013Full::DR_128);               // Set data rate, (dr 0–6) → bool
    float low = 0.0f, high = 0.0f;
    for (int i = 0; i < LOG_SAMPLES; i++) {
        int16_t raw = adc.readRaw();                    // Read raw code, () → int16 (−2048…2047)
        float v = adc.readVoltage();                    // Read voltage, () → V
        float battery = v * DIVIDER_RATIO;
        if (i == 0 || battery < low) low = battery;
        if (i == 0 || battery > high) high = battery;
        printk("raw %d divider %.3f V battery %.3f V (min %.3f, max %.3f)\n",
          (int)raw, (double)v, (double)battery, (double)low, (double)high);
        k_msleep(1000);
    }

    // --- Fast continuous averaging ---
    // At 3300 SPS in continuous mode, 100 samples take about 30 ms.
    adc.setDataRate(ADS1013Full::DR_3300);              // Set data rate, (dr 0–6) → bool
    adc.startContinuous();                              // Start continuous conversion, () → void
    k_msleep(10);
    float total = 0.0f;
    for (int i = 0; i < 100; i++) {
        total += adc.readContinuous();                  // Read latest voltage, () → V
        k_msleep(1);
    }
    adc.stopContinuous();                               // Stop continuous conversion, () → void
    printk("average battery %.3f V\n", (double)(total / 100.0f * DIVIDER_RATIO));
    return 0;
}
