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
    I2CConnection connection(Wire, ADS1013Minimal::I2C_ADDRESS);
    ADS1013Full adc(connection);                        // Create ADS1013 Full driver, (connection)
                                                        // no I2C traffic; fixed ±2.048 V, 1600 SPS

    int16_t raw = adc.readRaw();                        // Read raw code, () → int16 (−2048…2047)
                                                        // single-shot AIN0 − AIN1, polls OS until idle
    float v = adc.readVoltage();                        // Read voltage, () → V
                                                        // raw × 0.001
    logPrintf("raw %d voltage %.4f V\n", (int)raw, (double)v);
    logPrintf("full scale %.3f V\n", (double)adc.fullScale());  // Read full-scale range, () → V
                                                        // always 2.048
    logPrintf("lsb %.6f V\n", (double)adc.lsb());       // Read code size, () → V
                                                        // always 0.001

    adc.setDataRate(ADS1013Full::DR_250);               // Set data rate, (dr 0–6) → bool
                                                        // DR_250 = 250 SPS
    logPrintf("dr code %u\n", (unsigned)adc.getDataRate());  // Read data-rate code, () → uint8
                                                        // cached DR[2:0]
    logPrintf("sps %u\n", (unsigned)adc.dataRateSps()); // Read data rate, () → SPS
                                                        // table lookup of the DR code

    adc.startContinuous();                              // Start continuous conversion, () → void
                                                        // MODE = 0 at the current data rate
    delay(20);
    logPrintf("continuous raw %d\n", (int)adc.readContinuousRaw());  // Read latest code, () → int16
                                                        // reads CONVERSION without touching Config
    logPrintf("continuous %.4f V\n", (double)adc.readContinuous());  // Read latest voltage, () → V
                                                        // raw × lsb()
    adc.stopContinuous();                               // Stop continuous conversion, () → void
                                                        // MODE = 1, back to single-shot
    logPrintf("converting %d\n", adc.isConverting());   // Check conversion in progress, () → bool
                                                        // CONFIG.OS = 0 (single-shot only)
    logPrintf("config 0x%04X\n", (unsigned)adc.readConfig());  // Read CONFIG register, () → uint16
                                                        // raw 16-bit register
    adc.reset();                                        // Reset to power-up defaults, () → void
                                                        // rewrites CONFIG = 0x8583
}

void loop() {}
