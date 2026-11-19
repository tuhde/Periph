#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "HS40xx.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x54);
    HS40xxFull sensor(connection);                                  // Create HS40xx driver, (connection) → void
    stdio_init_all();
    float t = 0, rh = 0;
    uint8_t st = 0;
    uint32_t id = 0;
    // --- Humidity watchdog ---
    // Single readings for 10 s, then periodic mode with an RH-high alert (breathe on the sensor).
    sensor.read_id(id);                                             // Read the 32-bit sensor ID, (id) → bool
    printf("ID 0x%lX\n", (unsigned long)(id));
    for (uint8_t n = 0; n < 5; n++) {
        sensor.read(t, rh);                                         // Read temperature and humidity, (t, rh) → bool °C, %RH
        printf("%.2f C, %.1f %%RH\n", t, rh);
        sleep_ms(2000);
    }
    sensor.set_alert_thresholds(60.0f, 55.0f);                      // Set alert thresholds, (rh_high, rh_high_reset, ...) → bool
    sensor.enable_interrupt(HS40xxFull::SOURCE_RH_HIGH);            // Enable alert sources, (source) → bool
    sensor.start_periodic(1.0f);                                    // Start periodic mode, (period_s) → bool
    for (uint8_t n = 0; n < 15; n++) {
        sleep_ms(1000);
        sensor.read_periodic(t, rh, st);                            // Fetch the latest periodic result, (t, rh, status) → bool
        printf("%.2f C, %.1f %%RH %s\n", t, rh, (st & HS40xxFull::SOURCE_RH_HIGH) ? "ALERT" : "ok");
    }
    sensor.stop_periodic();                                         // Stop periodic mode, () → bool
    sensor.soft_reset();                                            // Soft reset, () → bool
    while (true) {
        sleep_ms(1000);
    }
    return 0;
}
