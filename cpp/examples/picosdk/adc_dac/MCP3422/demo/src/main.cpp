#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MCP3422.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, MCP3422Minimal::I2C_ADDRESS);
    MCP3422Full adc(connection);                        // Create MCP3422 Full driver, (connection)
    // --- CH1: thermistor divider at 16 bit / x1 ---
    for (int i = 0; i < 3; i++) {
        int32_t raw = adc.readRaw(0);                   // One-shot code, (channel 0–1) → int32_t
        float v = adc.readVoltage(0);                   // One-shot voltage, (channel 0–1) → float V
        printf("CH1 raw %d = %.5f V\n", (int)raw, (double)v);
        sleep_ms(1000);
    }

    // --- CH2: bridge sensor at 18 bit / x8 ---
    adc.setResolution(18);                              // Select 18 bit / 3.75 SPS, (bits) → bool
    adc.setGain(8);                                     // Select PGA x8, (gain) → bool
    const uint8_t ch2[1] = {1};
    float bridge[1];
    adc.readScan(ch2, 1, bridge);                       // Scan channels, (channels, count, float out[]) → bool
    printf("CH2 %.1f uV, LSB %.3f uV\n", (double)bridge[0] * 1e6f, (double)adc.lsb() / adc.getGain() * 1e6f);

    // --- Continuous conversion on CH1 at 12 bit / 240 SPS ---
    adc.setResolution(12);                              // Back to 12 bit / 240 SPS, (bits) → bool
    adc.setGain(1);                                     // Back to x1, (gain) → bool
    adc.startContinuous(0);                             // Start continuous conversion, (channel=-1) → void
    float lo = 1e9f, hi = -1e9f, sum = 0;
    for (int i = 0; i < 20; i++) {
        float v = adc.readContinuous();                 // Next fresh voltage, () → float V
        if (v < lo) lo = v;
        if (v > hi) hi = v;
        sum += v;
    }
    adc.stopContinuous();                               // Back to one-shot / standby, () → void
    printf("min %.5f max %.5f mean %.5f V\n", (double)lo, (double)hi, (double)sum / 20.0f);
    adc.generalCallReset();                             // Reset to power-on defaults, () → void
    return 0;
}
