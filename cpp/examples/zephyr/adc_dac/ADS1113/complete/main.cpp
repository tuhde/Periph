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
                                                            // no I²C traffic; Config word is rebuilt per conversion
    int16_t raw = adc.readRaw();                            // Read raw code, () → int16_t
                                                            // single-shot conversion, signed 16-bit
    printk("raw %d\n", (int)raw);
    float v = adc.readVoltage();                            // Read voltage, () → float V
                                                            // raw × lsb()
    printk("voltage %.5f V\n", (double)v);
    float fs = adc.fullScale();                             // Full scale, () → float V
                                                            // fixed ±2.048 V
    float lsb = adc.lsb();                                  // LSB, () → float V
                                                            // fixed 62.5 µV
    printk("fsr %.3f lsb %.7f\n", (double)fs, (double)lsb);
    adc.setDataRate(ADS1113Full::DR_250);                   // Set data rate, (dr 0–7) → void
                                                            // DR field of the Config word
    uint16_t sps = adc.dataRateSps();                       // Data rate, () → uint16_t SPS
                                                            // samples per second of the current setting
    printk("rate %u SPS\n", (unsigned)sps);
    adc.startContinuous();                                  // Start continuous, () → void
                                                            // MODE ← 0
    k_msleep(50);
    int16_t craw = adc.readContinuousRaw();                 // Read continuous raw, () → int16_t
                                                            // CONVERSION without touching Config
    float cv = adc.readContinuous();                        // Read continuous, () → float V
                                                            // readContinuousRaw() × lsb()
    printk("continuous %d %.5f V\n", (int)craw, (double)cv);
    adc.stopContinuous();                                   // Stop continuous, () → void
                                                            // MODE ← 1
    bool busy = adc.isConverting();                         // Check conversion, () → bool
                                                            // CONFIG.OS == 0
    uint16_t cfg = adc.readConfig();                        // Read CONFIG, () → uint16_t
                                                            // raw 16-bit register
    printk("converting %d config 0x%04X\n", (int)busy, (unsigned)cfg);
    adc.reset();                                            // Reset, () → void
                                                            // writes the reset values back
    return 0;
}
