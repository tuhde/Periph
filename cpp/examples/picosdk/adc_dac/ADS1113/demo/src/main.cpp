#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "ADS1113.h"

int main(void) {
    stdio_init_all();
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, ADS1113Minimal::I2C_ADDRESS);
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
        sleep_ms(1000);
    }

    // --- Average continuous readings ---
    // Back-to-back conversions averaged over 1.25 s reduce random noise.
    adc.startContinuous();                                  // Start continuous, () → void
    sleep_ms(200);
    float acc = 0.0f;
    for (int i = 0; i < 10; i++) {
        acc += adc.readContinuous();                        // Read continuous, () → float V
        sleep_ms(125);
    }
    adc.stopContinuous();                                   // Stop continuous, () → void
    printf("average %.5f V\n", (double)(acc / 10.0f));
    return 0;
}
