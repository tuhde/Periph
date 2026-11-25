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
    int32_t raw = adc.readRaw(0);                       // One-shot code, (channel 0–1) → int32_t
    // writes RDY = 1, O/C = 0, waits, polls RDY
    float v = adc.readVoltage(1);                       // One-shot voltage, (channel 0–1) → float V
    float both[2];
    adc.readAll(both);                                  // Both channels, (float out[2]) → bool
    const uint8_t scan[3] = {0, 1, 0};
    float scanned[3];
    adc.readScan(scan, 3, scanned);                     // Scan a list of channels, (channels, count, float out[]) → bool
    printf("raw %d v %.6f last ok %d\n", (int)raw, (double)v, (int)adc.lastReadOk());

    bool a = adc.setResolution(18);                     // Select 18 bit / 3.75 SPS, (bits 12|14|16|18) → bool
    // false for other values; nothing changes
    bool b = adc.setGain(8);                            // Select PGA x8, (gain 1|2|4|8) → bool
    printf("set %d %d bits %d gain %d\n", (int)a, (int)b, adc.getResolution(), adc.getGain());
    float lsb = adc.lsb();                              // LSB before gain, () → float V
    // 2·2.048/2^bits
    float fs = adc.fullScale();                         // Full-scale range, () → float V
    // 2.048/gain
    printf("lsb %.9f full scale %.3f\n", (double)lsb, (double)fs);

    adc.setResolution(12);                              // Back to 12 bit / 240 SPS, (bits) → bool
    adc.setGain(1);                                     // Back to x1, (gain) → bool
    adc.setChannel(0);                                  // Channel for continuous conversion, (channel 0–1) → void
    adc.startContinuous();                              // Start continuous conversion, (channel=-1) → void
    // O/C = 1; the first sample after a change is discarded
    int32_t cont = adc.readContinuousRaw();             // Next fresh code, () → int32_t
    float contV = adc.readContinuous();                 // Next fresh voltage, () → float V
    uint8_t cfg = adc.readConfig();                     // Read the config byte, () → uint8_t
    bool ready = adc.isReady();                         // Report RDY = 0, () → bool
    bool mode = adc.isContinuous();                     // Report the mode, () → bool
    printf("cont %d %.6f cfg %d ready %d continuous %d\n", (int)cont, (double)contV, (int)cfg, (int)ready, (int)mode);
    adc.stopContinuous();                               // Back to one-shot / standby, () → void

    adc.generalCallConvert();                           // Start a conversion on every device, () → void
    // sends 0x00 0x08
    sleep_ms(100);
    int32_t pending = adc.readPendingRaw();             // Result of that conversion, () → int32_t
    printf("pending %d\n", (int)pending);
    adc.generalCallReset();                             // Reset to power-on defaults, () → void
    // sends 0x00 0x06; the driver state follows
    return 0;
}
