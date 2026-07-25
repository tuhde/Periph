#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "MCP4728.h"

// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
i2c_init(i2c0, 100 * 1000);
gpio_set_function(4, GPIO_FUNC_I2C);
gpio_set_function(5, GPIO_FUNC_I2C);
gpio_pull_up(4);
gpio_pull_up(5);
I2CTransportPicoSDK transport(i2c0, 0x60);
MCP4728Full dac(transport);

int main(void) {
    const float STEP = 1.0f / 16.0f;
    const unsigned long DELAY_MS = 50;

    stdio_init_all();
    while (true) {

    // Apply four-point calibration voltages to channels A–D
    float calibration[4] = {0.0f, 1.0f / 3.0f, 2.0f / 3.0f, 1.0f};
    dac.set_all(calibration);
    for (uint8_t ch = 0; ch < 4; ch++) {
        uint16_t code = (uint16_t)(calibration[ch] * 4095.0f + 0.5f);
        printf("ch=");
        printf("%d", ch);
        printf(" fraction=");
        printf("%.4f", calibration[ch]);
        printf(" code=");
        printf("%d", code);
        printf(" approx_v=");
        printf("%.3f", code * 3.3f / 4096.0f);
        printf("V\n");
    }
    sleep_ms(500);

    // Synchronous staircase from 0 to full scale on all four channels
    for (int n = 0; n <= 16; n++) {
        float f = n * STEP;
        float fractions[4] = {f, f, f, f};
        dac.set_all(fractions);
        uint16_t code = (uint16_t)(f * 4095.0f + 0.5f);
        printf("step=");
        printf("%d", n);
        printf(" fraction=");
        printf("%.4f", f);
        printf(" code=");
        printf("%d", code);
        printf(" approx_v=");
        printf("%.3f", code * 3.3f / 4096.0f);
        printf("V\n");
        delay(DELAY_MS);
    }

    // Reset all channels to 0 V before next loop iteration
    float zero[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    dac.set_all(zero);
        sleep_ms(10);
    }

    return 0;
}
