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

static void onAlert(uint8_t status) { (void)status; }

void setup() {
    Serial.begin(115200);
    Wire.begin();
    I2CConnection connection(Wire, ADS1014Minimal::I2C_ADDRESS);
    ADS1014Full adc(connection);                        // Create ADS1014 Full driver, (connection)
                                                        // no I2C traffic; defaults ±2.048 V, 1600 SPS

    int16_t raw = adc.readRaw();                        // Read raw code, () → int16 (−2048…2047)
                                                        // single-shot AIN0 − AIN1, polls OS until idle
    float v = adc.readVoltage();                        // Read voltage, () → V
                                                        // raw × full-scale / 2048
    logPrintf("raw %d voltage %.4f V\n", (int)raw, (double)v);

    adc.setGain(ADS1014Full::PGA_1_024);                // Set PGA, (pga 0–5) → bool
                                                        // ±1.024 V range, 0.5 mV per code
    logPrintf("gain %u\n", (unsigned)adc.getGain());    // Read PGA code, () → uint8
                                                        // cached PGA[2:0]
    logPrintf("full scale %.3f V\n", (double)adc.fullScale());  // Read full-scale range, () → V
                                                        // ±FSR of the current gain
    logPrintf("lsb %.6f V\n", (double)adc.lsb());       // Read code size, () → V
                                                        // fullScale() / 2048

    adc.setDataRate(ADS1014Full::DR_250);               // Set data rate, (dr 0–6) → bool
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

    adc.setThresholds(-0.2f, 0.5f);                     // Set both thresholds, (low V, high V) → bool
                                                        // converted with lsb(), clamped to the code range
    adc.setLowThresholdRaw(-200);                       // Set low threshold, (raw −2048…2047) → bool
                                                        // LO_THRESH = raw << 4
    adc.setHighThresholdRaw(500);                       // Set high threshold, (raw −2048…2047) → bool
                                                        // HI_THRESH = raw << 4
    int16_t lo = 0, hi = 0;
    adc.getThresholdsRaw(&lo, &hi);                     // Read thresholds, (low*, high*) → void
                                                        // sign-extended 12-bit codes
    logPrintf("thresholds %d %d\n", (int)lo, (int)hi);

    adc.configureComparator(ADS1014Full::COMP_WINDOW, ADS1014Full::ACTIVE_LOW, true, ADS1014Full::QUEUE_2);  // Configure comparator, (mode, polarity, latch, queue) → bool
                                                        // window mode, latching, 2 consecutive conversions
    adc.clearAlert();                                   // Release latched alert, () → void
                                                        // reads CONVERSION
    adc.disableComparator();                            // Disable comparator, () → void
                                                        // COMP_QUE = 11, ALERT/RDY high-impedance

    adc.enableInterrupt(ADS1014Full::SOURCE_CONVERSION_READY);  // Route conversion-ready to ALERT/RDY, (source) → bool
                                                        // HI_THRESH = 0x8000, LO_THRESH = 0, COMP_QUE = 00
    logPrintf("status %u\n", (unsigned)adc.pollInterrupt());  // Poll ALERT/RDY, () → uint8 source
                                                        // INT pin level, else CONFIG.OS fallback
    adc.disableInterrupt(ADS1014Full::SOURCE_CONVERSION_READY);  // Release ALERT/RDY, (source) → void
                                                        // COMP_QUE = 11

    adc.enableInterrupt(ADS1014Full::SOURCE_COMPARATOR);  // Route comparator to ALERT/RDY, (source) → bool
                                                        // restores the thresholds set above
    adc.onInterrupt(onAlert);                           // Subscribe to ALERT/RDY, (callback, intPin=nullptr) → void
                                                        // pin edge via connection.intPin(); no-op without a pin
    delay(1000);
    adc.offInterrupt();                                 // Unsubscribe, () → void
                                                        // detaches the edge handler
    adc.reset();                                        // Reset to power-up defaults, () → void
                                                        // rewrites CONFIG and both thresholds
}

void loop() {}
