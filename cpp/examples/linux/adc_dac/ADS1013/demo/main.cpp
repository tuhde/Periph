#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADS1013.h"

// Battery-voltage logger: the cell is read through a 2:1 resistor divider
// (AIN0, AIN1 to GND).

static const float DIVIDER_RATIO = 2.0f;
static const int LOG_SAMPLES = 10;

int main() {
    I2CConnectionLinux connection(1, ADS1013Minimal::I2C_ADDRESS);
    ADS1013Full adc(connection);                        // Create ADS1013 Full driver, (connection)

    // --- Slow, low-noise single-shot logging ---
    // 128 SPS gives the lowest noise; one conversion per second keeps the chip
    // in power-down in between.
    adc.setDataRate(ADS1013Full::DR_128);               // Set data rate, (dr 0–6) → bool
    float low = 0.0f, high = 0.0f;
    for (int i = 0; i < LOG_SAMPLES; i++) {
        int16_t raw = adc.readRaw();                    // Read raw code, () → int16 (−2048…2047)
        float v = adc.readVoltage();                    // Read voltage, () → V
        float battery = v * DIVIDER_RATIO;
        if (i == 0 || battery < low) low = battery;
        if (i == 0 || battery > high) high = battery;
        printf("raw %d divider %.3f V battery %.3f V (min %.3f, max %.3f)\n",
          (int)raw, (double)v, (double)battery, (double)low, (double)high);
        usleep(1000UL * 1000UL);
    }

    // --- Fast continuous averaging ---
    // At 3300 SPS in continuous mode, 100 samples take about 30 ms.
    adc.setDataRate(ADS1013Full::DR_3300);              // Set data rate, (dr 0–6) → bool
    adc.startContinuous();                              // Start continuous conversion, () → void
    usleep(10UL * 1000UL);
    float total = 0.0f;
    for (int i = 0; i < 100; i++) {
        total += adc.readContinuous();                  // Read latest voltage, () → V
        usleep(1UL * 1000UL);
    }
    adc.stopContinuous();                               // Stop continuous conversion, () → void
    printf("average battery %.3f V\n", (double)(total / 100.0f * DIVIDER_RATIO));
    return 0;
}
