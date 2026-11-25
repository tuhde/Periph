#include <Wire.h>
#include <Periph.h>
#include <stdio.h>

I2CConnection connection(Wire, MCP3422Full::I2C_ADDRESS);
MCP3422Full adc(connection);                            // Create MCP3422 Full driver, (connection)

void setup() {
    Wire.begin();
    Serial.begin(115200);
    // --- CH1: thermistor divider at 16 bit / x1 ---
    for (int i = 0; i < 3; i++) {
        int32_t raw = adc.readRaw(0);                   // One-shot code, (channel 0–1) → int32_t
        float v = adc.readVoltage(0);                   // One-shot voltage, (channel 0–1) → float V
        Serial.print("CH1 raw "); Serial.print((int)raw); Serial.print(" = "); Serial.print((double)v, 5); Serial.println(" V");
        delay(1000);
    }

    // --- CH2: bridge sensor at 18 bit / x8 ---
    adc.setResolution(18);                              // Select 18 bit / 3.75 SPS, (bits) → bool
    adc.setGain(8);                                     // Select PGA x8, (gain) → bool
    const uint8_t ch2[1] = {1};
    float bridge[1];
    adc.readScan(ch2, 1, bridge);                       // Scan channels, (channels, count, float out[]) → bool
    Serial.print("CH2 "); Serial.print((double)bridge[0] * 1e6f, 1); Serial.print(" uV, LSB "); Serial.print((double)adc.lsb() / adc.getGain() * 1e6f, 3); Serial.println(" uV");

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
    Serial.print("min "); Serial.print((double)lo, 5); Serial.print(" max "); Serial.print((double)hi, 5); Serial.print(" mean "); Serial.print((double)sum / 20.0f, 5); Serial.println(" V");
    adc.generalCallReset();                             // Reset to power-on defaults, () → void
}

void loop() {
}
