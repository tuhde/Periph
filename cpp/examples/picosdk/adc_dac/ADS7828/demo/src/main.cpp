#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "ADS7828.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x48);
    ADS7828Full adc(connection);                            // Create ADS7828 driver, (connection, vref=0 → internal 2.5 V)

    stdio_init_all();
    while (true) {
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
                printf("ch%d=%u (%.3f V)  ", ch, raws[ch], (double)volts);
            }
            printf("\n");
            sleep_ms(1000);
        }
        for (int ch = 0; ch < 8; ch++) printf("ch%d min=%.3f max=%.3f\n", ch, (double)vmin[ch], (double)vmax[ch]);

        // --- Read a bridge sensor on CH0/CH1 ---
        // The signed differential read does two conversions (normal and inverted
        // polarity) so the sign of the bridge output is preserved.
        float mv = adc.readSignedDifferential(0) * 1000.0f;         // Read pair 0 signed, (pair=0–3) → float V
        printf("bridge %+.2f mV\n", (double)mv);

        // --- Second scan against an external 5.0 V reference ---
        // Switching to a 5 V external reference gives full-scale range up to VDD.
        adc.setReference(5.0f);                                     // Use external reference, (vref V; 0 = internal) → void
        uint16_t ext[8];
        adc.readScan(chans, 8, ext);                                // Scan channels, (channels, count, out) → void
        printf("ext 5 V scan ch0=%u ch7=%u\n", ext[0], ext[7]);

        // --- Finish in full power-down ---
        adc.powerDown();                                            // Power down completely, () → void
        adc.setReference(0.0f);
        sleep_ms(1000);
    }
    return 0;
}
