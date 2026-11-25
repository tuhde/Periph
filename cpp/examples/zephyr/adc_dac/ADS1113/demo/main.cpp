#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ADS1113.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, ADS1113Minimal::I2C_ADDRESS);
    ADS1113Full adc(connection);                            // Create ADS1113 Full driver, (connection)

    // --- Configure for low-noise slow sampling ---
    // 8 SPS gives the longest integration time per conversion, which
    // suppresses mains and switching noise on a slowly varying voltage.
    adc.setDataRate(ADS1113Full::DR_8);                     // Set data rate, (dr 0–7) → void

    // --- Log single-shot readings ---
    // Each readVoltage() wakes the chip, converts and powers down again.
    for (int i = 0; i < 5; i++) {
        float v = adc.readVoltage();                        // Read voltage, () → float V
        printk("%.5f V\n", (double)v);
        k_msleep(1000);
    }

    // --- Average continuous readings ---
    // Back-to-back conversions averaged over 1.25 s reduce random noise.
    adc.startContinuous();                                  // Start continuous, () → void
    k_msleep(200);
    float acc = 0.0f;
    for (int i = 0; i < 10; i++) {
        acc += adc.readContinuous();                        // Read continuous, () → float V
        k_msleep(125);
    }
    adc.stopContinuous();                                   // Stop continuous, () → void
    printk("average %.5f V\n", (double)(acc / 10.0f));
    return 0;
}
