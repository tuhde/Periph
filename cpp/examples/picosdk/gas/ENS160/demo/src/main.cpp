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
    ENS160Full sensor(transport);

    static int passed = 0, failed = 0;
            case 1: return "Excellent";
            case 2: return "Good";
            case 3: return "Moderate";
            case 4: return "Poor";
            case 5: return "Unhealthy";
            default: return "Unknown";

    stdio_init_all();
    sleep_ms(2000);

    // --- Wait for sensor warm-up ---
    // The ENS160 requires ~3 minutes after power-on or idle before VALIDITY_FLAG
    // reaches 0. During warm-up, readings are unreliable. The driver surfaces the
    // status so the application can display progress to the user.
    printf("Waiting for sensor warm-up...\n");
    {
        uint8_t _aqi; float _tvoc, _eco2;
        while (!sensor.read_air_quality(_aqi, _tvoc, _eco2)) {  // Wait for valid data, () → blocks until warm
            uint8_t s = sensor.status();
            if (s == 1) printf("Warm-up in progress...\n");
            else if (s == 2) printf("Initial start-up (first power-on, up to 1 hour)...\n");
            else printf("No valid output\n");
            sleep_ms(1000);
        }
    }
    printf("Sensor ready!\n");

    // --- Set compensation from external sensor ---
    // If an external temperature/humidity sensor is available, feeding its readings
    // to the ENS160 improves accuracy outside the 20-80%RH range. Here we use a
    // fixed 22C/45%RH as an example.
    sensor.set_compensation(22.0f, 45.0f);               // Set compensation, (temp_celsius=22.0, rh_percent=45.0) → void

    // --- Indoor air quality monitoring loop ---
    // Reads AQI, TVOC, and eCO2 every second and prints a human-readable label.
    // AQI 1-2 is acceptable for occupied spaces; AQI 3+ suggests ventilation.
    for (int n = 0; n < 60; n++) {
        uint8_t aqi;
        float tvoc_ppb, eco2_ppm;
        bool ok = sensor.read_air_quality(aqi, tvoc_ppb, eco2_ppm);  // Read air quality, () → bool
        if (ok) {
            printf("%d", n);
            printf("s: AQI=");
            printf("%d", aqi);
            printf(" (");
            printf("%d", aqi_label(aqi));
            printf(") TVOC=");
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
