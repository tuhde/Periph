#include <cstdio>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ADS1113.h"

int main() {
    I2CConnectionLinux connection(1, ADS1113Minimal::I2C_ADDRESS);
    ADS1113Full adc(connection);                            // Create ADS1113 Full driver, (connection)

    // --- Configure for low-noise slow sampling ---
    // 8 SPS gives the longest integration time per conversion, which
    // suppresses mains and switching noise on a slowly varying voltage.
    adc.setDataRate(ADS1113Full::DR_8);                     // Set data rate, (dr 0–7) → void

    // --- Log single-shot readings ---
    // Each readVoltage() wakes the chip, converts and powers down again.
    for (int i = 0; i < 5; i++) {
        float v = adc.readVoltage();                        // Read voltage, () → float V
        printf("%.5f V\n", (double)v);
        usleep(1000UL * 1000UL);
    }

    // --- Average continuous readings ---
    // Back-to-back conversions averaged over 1.25 s reduce random noise.
    adc.startContinuous();                                  // Start continuous, () → void
    usleep(200UL * 1000UL);
    float acc = 0.0f;
    for (int i = 0; i < 10; i++) {
        acc += adc.readContinuous();                        // Read continuous, () → float V
        usleep(125UL * 1000UL);
    }
    adc.stopContinuous();                                   // Stop continuous, () → void
    printf("average %.5f V\n", (double)(acc / 10.0f));
    return 0;
}
