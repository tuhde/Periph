#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "INA3221.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x40);
INA3221Full ina(transport, /*r_shunt=*/0.1f);

int main(void) {

    stdio_init_all();


    // --- Monitor three rails simultaneously ---
    // User wires CH1 to 5V rail, CH2 to 3.3V rail, CH3 to 12V rail.
    // The demo prints a one-line tabular update each second for 30 seconds.
    printf("V1       I1       P1       V2       I2       P2       V3       I3       P3\n");
    for (int t = 0; t < 30; t++) {
        for (uint8_t ch = 1; ch <= 3; ch++) {
            float v = ina.voltage(ch);                // Read bus voltage, (channel) → float V
            float i = ina.current(ch);                // Read load current, (channel) → float A
            float p = ina.power(ch);                  // Read power, (channel) → float W
            printf("%.3f", v); printf(" ");
            printf("%.4f", i); printf(" ");
            printf("%.4f", p); printf("   ");
        }
        printf("\n");

        if (t == 9) {
            // --- Arm critical-alert limits at 1.5x current draw ---
            for (uint8_t ch = 1; ch <= 3; ch++) {
                float i = ina.current(ch);
                ina.set_critical_alert(ch, i * 1.5f);
            }
            printf("alerts armed\n");
        }

        if (t == 19) {
            // --- Arm shunt-voltage summation across all three channels ---
            uint8_t channels[] = {1, 2, 3};
            ina.set_summation_channels(channels, 3, 0.3f);  // Set summation channels, (channels, n, limit_v) → None
                                                             // configures SCC bits and sum limit register
            printf("summation armed\n");
        }

        sleep_ms(1000);
    }

    // --- Dump alert flags and decode any that fired ---
    uint16_t flags = ina.alert_flags();               // Read alert flags, () → int
                                                       // reads Mask/Enable register, clears latched flags
    printf("Mask/Enable: 0x");
    printf("0x%X\n", (unsigned)flags);
    return 0;
}
