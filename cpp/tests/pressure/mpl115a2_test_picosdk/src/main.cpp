#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MPL115A2.h"

int passed = 0;
int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_near(float v, float lo, float hi, const char *label) {
    if (v >= lo && v <= hi) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: %.4f not in [%.4f, %.4f]\n", label, (double)v, (double)lo, (double)hi); failed++; }
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x60);
    MPL115A2Full baro(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    float p = baro.read_pressure();
    check_true(p >= 50000.0f && p <= 115000.0f, "pressure_range");
    float t = baro.read_temperature();
    check_true(t >= -40.0f && t <= 105.0f, "temperature_range");
    uint16_t padc, tadc;
    baro.read_raw(padc, tadc);
    check_true(padc <= 1023 && tadc <= 1023, "raw_range");
    float c = baro.compensate(padc, tadc);
    check_true(c >= 50000.0f && c <= 115000.0f, "compensate_range");
    float pa, tc;
    baro.read(pa, tc);
    check_true(pa >= 50000.0f && pa <= 115000.0f && tc >= -40.0f && tc <= 105.0f, "read_both");
    float hpa = baro.pressure_hpa();
    check_true(hpa >= 500.0f && hpa <= 1150.0f, "pressure_hpa");
    float alt = baro.altitude();
    check_true(alt >= -1000.0f && alt <= 6000.0f, "altitude");
    baro.reload_coefficients();
    float a0, b1, b2, c12;
    baro.coefficients(a0, b1, b2, c12);
    check_true(a0 != 0.0f, "coefficients");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
