#include <stdarg.h>
#include <Wire.h>
#include <Periph.h>

static void logPrintf(const char* fmt, ...) {
    char buf[128];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    Serial.print(buf);
}

void setup() {
    Serial.begin(115200);
    Wire.begin();
    I2CConnection connection(Wire, ADS1113Minimal::I2C_ADDRESS);
    ADS1113Full adc(connection);                            // Create ADS1113 Full driver, (connection)

    // --- Configure for low-noise slow sampling ---
    // 8 SPS gives the longest integration time per conversion, which
    // suppresses mains and switching noise on a slowly varying voltage.
    adc.setDataRate(ADS1113Full::DR_8);                     // Set data rate, (dr 0–7) → void

    // --- Log single-shot readings ---
    // Each readVoltage() wakes the chip, converts and powers down again.
    for (int i = 0; i < 5; i++) {
        float v = adc.readVoltage();                        // Read voltage, () → float V
        logPrintf("%.5f V\n", (double)v);
        delay(1000);
    }

    // --- Average continuous readings ---
    // Back-to-back conversions averaged over 1.25 s reduce random noise.
    adc.startContinuous();                                  // Start continuous, () → void
    delay(200);
    float acc = 0.0f;
    for (int i = 0; i < 10; i++) {
        acc += adc.readContinuous();                        // Read continuous, () → float V
        delay(125);
    }
    adc.stopContinuous();                                   // Stop continuous, () → void
    logPrintf("average %.5f V\n", (double)(acc / 10.0f));
}

void loop() {}
