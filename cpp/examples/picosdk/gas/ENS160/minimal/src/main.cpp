#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "ENS160.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x53);
    ENS160Minimal sensor(transport);

    static int passed = 0, failed = 0;

    stdio_init_all();
    sleep_ms(2000);

    printf("Waiting for sensor warm-up...\n");
    {
        uint8_t _aqi; float _tvoc, _eco2;
        while (!sensor.read_air_quality(_aqi, _tvoc, _eco2)) {  // Wait for valid data, () → blocks until warm
            sleep_ms(1000);
        }
    }

    for (int i = 0; i < 10; i++) {
        uint8_t aqi;
        float tvoc_ppb, eco2_ppm;
        bool ok = sensor.read_air_quality(aqi, tvoc_ppb, eco2_ppm);  // Read air quality, (aqi&, tvoc_ppb&, eco2_ppm&) → bool
        if (ok) {
            printf("AQI=");
            printf("%d", aqi);
            printf(" TVOC=");
            printf("%.0f", tvoc_ppb);
            printf(" ppb eCO2=");
            printf("%.0f", eco2_ppm);
            printf(" ppm\n");
        }
        sleep_ms(1000);
    }

    printf("===DONE: ");
    printf("%d", passed);
    printf(" passed, ");
    printf("%d", failed);
    printf(" failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
