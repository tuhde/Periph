#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "MCP4725.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x60);
    MCP4725Full dac(transport);

    const float STEP = 1.0f / 20.0f;
    const unsigned long DELAY_MS = 100;

    stdio_init_all();
    while (true) {

    for (int n = 0; n <= 20; n++) {
        float fraction = n * STEP;
        dac.set_voltage(fraction);
        uint16_t code = (uint16_t)(fraction * 4095.0f + 0.5f);
        float approx_v = code * 3.3f / 4096.0f;
        printf("n=");
        printf("%d", n);
        printf(" fraction=");
        printf("%f", fraction);
        printf(" code=");
        printf("%d", code);
        printf(" approx_v=");
        printf("%.3f", approx_v);
        printf("V\n");
        delay(DELAY_MS);
    }
    for (int n = 20; n >= 0; n--) {
        float fraction = n * STEP;
        dac.set_voltage(fraction);
        uint16_t code = (uint16_t)(fraction * 4095.0f + 0.5f);
        float approx_v = code * 3.3f / 4096.0f;
        printf("n=");
        printf("%d", n);
        printf(" fraction=");
        printf("%f", fraction);
        printf(" code=");
        printf("%d", code);
        printf(" approx_v=");
        printf("%.3f", approx_v);
        printf("V\n");
        delay(DELAY_MS);
    }
        sleep_ms(10);
    }

    return 0;
}
