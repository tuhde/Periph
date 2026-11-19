#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "HS30xx.h"

// Replace with code that cuts and restores the sensor supply (MOSFET or GPIO).
static void power_cycle() { sleep_ms(100); }

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x44);
    HS30xxFull sensor(connection);                                  // Create HS30xx driver, (connection) → void
    stdio_init_all();
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0;
    uint8_t status = 0, hb = 0, tb = 0;
    uint32_t id = 0;
    uint16_t reg = 0;
    bool ok = false;
    sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                    // request, 40 ms wait, 4-byte fetch; one retry on stale data
    printf("%.2f C, %.1f %%RH\n", t, rh);
    t = sensor.read_temperature();                                  // Read temperature only, () → float °C
                                                                    // NAN on failure
    printf("temperature %.2f C\n", t);
    rh = sensor.read_humidity();                                    // Read humidity only, () → float %RH
                                                                    // 2-byte fetch, NAN on failure
    printf("humidity %.1f %%RH\n", rh);
    sensor.read_raw(rh_raw, t_raw, status);                         // Read raw values, (humidity_raw, temperature_raw, status) → bool
                                                                    // 14-bit values; status 0 = valid, 1 = stale
    printf("raw RH %lu raw T %lu status %lu\n", (unsigned long)(rh_raw), (unsigned long)(t_raw), (unsigned long)(status));
    sensor.set_measurement_wait(60);                                // Override the post-request wait, (ms) → void
                                                                    // default 40 ms; lower resolutions need less
    sensor.set_measurement_wait(40);                                // Back to the default wait
    t = sensor.dew_point();                                         // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula
    printf("dew point %.2f C\n", t);
    // NVM access only works within 10 ms after power-up, so it needs the power_cycle helper above.
    ok = sensor.get_resolution(hb, tb, power_cycle);                // Get NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
                                                                    // bits are 8, 10, 12 or 14
    printf("resolution %lu/%lu bit (ok=%lu)\n", (unsigned long)(hb), (unsigned long)(tb), (unsigned long)(ok));
    sensor.set_resolution(12, 12, power_cycle);                     // Set NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
                                                                    // 8, 10, 12 or 14 bit; 0 leaves a channel unchanged
    ok = sensor.read_id(id, power_cycle);                           // Read the 32-bit sensor ID, (id, power_cycle) → bool
    printf("id 0x%lX (ok=%lu)\n", (unsigned long)(id), (unsigned long)(ok));
    sensor.enter_programming_mode();                                // Enter programming mode, () → void
                                                                    // only within 10 ms after power-up
    ok = sensor.read_nvm(0x06, reg);                                // Read an NVM register, (reg, value) → bool
                                                                    // 0x06 humidity / 0x11 temperature resolution, 0x1E / 0x1F ID
    printf("NVM 0x06 = 0x%lX (ok=%lu)\n", (unsigned long)(reg), (unsigned long)(ok));
    sensor.exit_programming_mode();                                 // Leave programming mode, () → void
    while (true) {
        sleep_ms(1000);
    }
    return 0;
}
