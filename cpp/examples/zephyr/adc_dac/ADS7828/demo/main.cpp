#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ADS7828.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define ADS7828_ADDR 0x48

const float VREF  = 3.3f;
const float VAGND = 0.0f;

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, ADS7828_ADDR);
    ADS7828Full adc(connection);                            // Create ADS7828 driver, (connection, vref=0 → internal 2.5 V)

    while (1) {
        const float DIVIDER = 2.0f;   // inputs sit behind 2:1 dividers (rails up to 5 V)
        const uint8_t chans[8] = {0, 1, 2, 3, 4, 5, 6, 7};

        // --- Eight-channel supply monitor ---
        // Scan CH0–CH7 with the internal 2.5 V reference, print raw codes and rail
        // voltages (after the divider ratio) and keep a running minimum/maximum per channel.
        float vmin[8], vmax[8];
        for (int ch = 0; ch < 8; ch++) { vmin[ch] = 99.0f; vmax[ch] = 0.0f; }
        for (int n = 0; n < 5; n++) {
            uint16_t raws[8];
            adc.readScan(chans, 8, raws);                           // Scan channels, (channels, count, out) → void
            for (int ch = 0; ch < 8; ch++) {
                float volts = raws[ch] * adc.lsb() * DIVIDER;
                if (volts < vmin[ch]) vmin[ch] = volts;
                if (volts > vmax[ch]) vmax[ch] = volts;
                printk("ch%d=%u (%.3f V)  ", ch, raws[ch], (double)volts);
            }
            printk("\n");
            k_msleep(1000);
        }
        for (int ch = 0; ch < 8; ch++) printk("ch%d min=%.3f max=%.3f\n", ch, (double)vmin[ch], (double)vmax[ch]);

        // --- Read a bridge sensor on CH0/CH1 ---
        // The signed differential read does two conversions (normal and inverted
        // polarity) so the sign of the bridge output is preserved.
        float mv = adc.readSignedDifferential(0) * 1000.0f;         // Read pair 0 signed, (pair=0–3) → float V
        printk("bridge %+.2f mV\n", (double)mv);

        // --- Second scan against an external 5.0 V reference ---
        // Switching to a 5 V external reference gives full-scale range up to VDD.
        adc.setReference(5.0f);                                     // Use external reference, (vref V; 0 = internal) → void
        uint16_t ext[8];
        adc.readScan(chans, 8, ext);                                // Scan channels, (channels, count, out) → void
        printk("ext 5 V scan ch0=%u ch7=%u\n", ext[0], ext[7]);

        // --- Finish in full power-down ---
        adc.powerDown();                                            // Power down completely, () → void
        adc.setReference(0.0f);
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
