#include <Wire.h>
#include <Periph.h>

I2CConnection connection(Wire, 0x48);
ADS7828Full adc(connection);

const float DIVIDER = 2.0f;   // inputs sit behind 2:1 dividers (rails up to 5 V)
const uint8_t chans[8] = {0, 1, 2, 3, 4, 5, 6, 7};

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    // --- Eight-channel supply monitor ---
    // Scan CH0–CH7 with the internal 2.5 V reference, print raw codes and rail
    // voltages (after the divider ratio) and keep a running minimum/maximum per channel.
    float vmin[8], vmax[8];
    for (int ch = 0; ch < 8; ch++) { vmin[ch] = 99.0f; vmax[ch] = 0.0f; }
    for (int n = 0; n < 5; n++) {
        uint16_t raws[8];
        adc.readScan(chans, 8, raws);                       // Scan channels, (channels, count, out) → void
        for (int ch = 0; ch < 8; ch++) {
            float volts = raws[ch] * adc.lsb() * DIVIDER;
            if (volts < vmin[ch]) vmin[ch] = volts;
            if (volts > vmax[ch]) vmax[ch] = volts;
            Serial.print("ch"); Serial.print(ch); Serial.print("=");
            Serial.print(raws[ch]); Serial.print(" ("); Serial.print(volts, 3); Serial.print(" V)  ");
        }
        Serial.println();
        delay(1000);
    }
    for (int ch = 0; ch < 8; ch++) {
        Serial.print("ch"); Serial.print(ch);
        Serial.print(" min="); Serial.print(vmin[ch], 3);
        Serial.print(" max="); Serial.println(vmax[ch], 3);
    }

    // --- Read a bridge sensor on CH0/CH1 ---
    // The signed differential read does two conversions (normal and inverted
    // polarity) so the sign of the bridge output is preserved.
    float mv = adc.readSignedDifferential(0) * 1000.0f;     // Read pair 0 signed, (pair=0–3) → float V
    Serial.print("bridge "); Serial.print(mv, 2); Serial.println(" mV");

    // --- Second scan against an external 5.0 V reference ---
    // Switching to a 5 V external reference gives full-scale range up to VDD.
    adc.setReference(5.0f);                                 // Use external reference, (vref V; 0 = internal) → void
    uint16_t ext[8];
    adc.readScan(chans, 8, ext);                            // Scan channels, (channels, count, out) → void
    Serial.print("ext 5 V scan ch0="); Serial.print(ext[0]); Serial.print(" ch7="); Serial.println(ext[7]);

    // --- Finish in full power-down ---
    adc.powerDown();                                        // Power down completely, () → void
    adc.setReference(0.0f);
}
