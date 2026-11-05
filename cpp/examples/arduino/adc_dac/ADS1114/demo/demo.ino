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
    I2CConnection connection(Wire, ADS1114Minimal::I2C_ADDRESS);
    ADS1114Full adc(connection);                            // Create ADS1114 Full driver, (connection)

    // --- Configure the converter ---
    // ±4.096 V covers a 3.3 V rail with headroom; 250 SPS keeps single-shot
    // reads short while still averaging out most noise.
    adc.setGain(ADS1114Full::PGA_4_096);                    // Set gain, (pga 0–5) → void
    adc.setDataRate(ADS1114Full::DR_250);                   // Set data rate, (dr 0–7) → void

    // --- Read the inputs ---
    // Single-shot mode powers the chip down between conversions.
    for (int i = 0; i < 3; i++) {
        int16_t raw = adc.readRaw();                        // Read raw code, () → int16_t
        logPrintf("raw %d %.5f V\n", (int)raw, (double)(raw * adc.lsb()));
        delay(1000);
    }

    // --- Arm a window comparator ---
    // ALERT/RDY asserts when the input leaves 0.5 V ... 3.0 V for two
    // consecutive conversions; the latch holds it until clearAlert().
    adc.setThresholds(0.5f, 3.0f);                          // Set thresholds, (low V, high V) → void
    adc.configureComparator(ADS1114Full::COMP_WINDOW, ADS1114Full::ACTIVE_LOW, true, ADS1114Full::QUEUE_2);// Configure comparator, (mode, polarity, latch=false, queue=QUEUE_1) → void
    adc.startContinuous();                                  // Start continuous, () → void

    // --- Watch for out-of-range events ---
    // Without an interrupt pin pollInterrupt() cannot see a comparator
    // alert, so read the conversion and compare in software as a fallback.
    for (int i = 0; i < 100; i++) {
        uint8_t status = adc.pollInterrupt();               // Poll interrupt, () → uint8_t source
        if (status) {
            logPrintf("OUT OF RANGE\n");
            adc.clearAlert();                               // Clear alert, () → void
        }
        delay(100);
    }
    adc.disableComparator();                                // Disable comparator, () → void
    adc.stopContinuous();                                   // Stop continuous, () → void
}

void loop() {}
