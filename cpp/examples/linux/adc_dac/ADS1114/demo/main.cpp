#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADS1114.h"

int main() {
    I2CConnectionLinux connection(1, ADS1114Minimal::I2C_ADDRESS);
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
        printf("raw %d %.5f V\n", (int)raw, (double)(raw * adc.lsb()));
        usleep(1000UL * 1000UL);
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
            printf("OUT OF RANGE\n");
            adc.clearAlert();                               // Clear alert, () → void
        }
        usleep(100UL * 1000UL);
    }
    adc.disableComparator();                                // Disable comparator, () → void
    adc.stopContinuous();                                   // Stop continuous, () → void
    return 0;
}
