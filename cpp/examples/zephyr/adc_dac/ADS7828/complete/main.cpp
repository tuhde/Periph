#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "ADS7828.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define ADS7828_ADDR 0x48

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, ADS7828_ADDR);
    ADS7828Full adc(connection);                            // Create ADS7828 driver, (connection, vref=0 → internal 2.5 V)

    while (1) {
        uint16_t raw = adc.readRaw(3);                              // Read CH3 raw code, (channel=0–7) → uint16_t
                                                            // single-ended, interleaved channel encoding; returns 0–4095
        float v3 = adc.readVoltage(3);                              // Read CH3 voltage, (channel=0–7) → float V
                                                            // raw / 4096 × vref
        float all[8];
        adc.readAll(all);                                           // Read all eight channels, (out[8]) → void
                                                            // one conversion per channel, CH0…CH7
        uint16_t draw = adc.readDifferentialRaw(0);                 // Read pair 0 raw code, (pair=0–3, inverted=false) → uint16_t
                                                            // CH0 (+) / CH1 (−); a negative difference reads 0
        float dv = adc.readDifferential(1, true);                   // Read pair 1 inverted, (pair=0–3, inverted=false) → float V
                                                            // CH3 (+) / CH2 (−)
        float sv = adc.readSignedDifferential(0);                   // Read pair 0 signed, (pair=0–3) → float V
                                                            // two conversions (normal and inverted), range −vref … +vref
        float lsb = adc.lsb();                                      // Get code step, () → float V
                                                            // vref / 4096 (610 µV at 2.5 V)
        const uint8_t chans[4] = {0, 2, 4, 6};
        uint16_t scan[4];
        adc.readScan(chans, 4, scan);                               // Scan channels, (channels, count, out) → void
                                                            // one raw conversion per channel in the given order
        adc.setReference(5.0f);                                     // Use external reference, (vref V; 0 = internal) → void
                                                            // PD1 = 0 from now on; the reference must already be stable
        float ref = adc.getReference();                             // Get reference voltage, () → float V
        bool internal = adc.usesInternalReference();                // Internal reference selected?, () → bool
        adc.setReference(0.0f);                                     // Use internal reference, (vref V; 0 = internal) → void
                                                            // re-runs the reference turn-on and 1 ms settle wait
        bool ok = adc.setPowerMode(ADS7828Full::REF_ON_ADC_ON);     // Set PD bits, (pd=0–3) → bool
                                                            // internal reference requires PD1 = 1 (values 2 or 3)
        adc.powerDown();                                            // Power down completely, () → void
                                                            // lone command with PD=00; next read re-enables the reference
        printk("raw=%u v3=%.3f V draw=%u dv=%.3f V sv=%.3f V lsb=%.6f V ref=%.2f V internal=%d ok=%d scan0=%u all7=%.3f V\n", raw, (double)v3, draw, (double)dv, (double)sv, (double)lsb, (double)ref, (int)internal, (int)ok, scan[0], (double)all[7]);
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
