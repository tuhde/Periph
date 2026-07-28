#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "INA226.h"

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
    I2CConnectionPicoSDK connection(i2c0, 0x40);
    INA226Full ina(connection, /*r_shunt=*/0.1f, /*max_current=*/2.0f);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    check_near(ina.voltage(),       0.0f, 40.0f,  "voltage range");
    check_near(ina.shunt_voltage(), -0.1f, 0.1f,  "shunt_voltage range");
    check_near(ina.current(),       -2.0f, 2.0f,  "current range");
    check_near(ina.power(),          0.0f, 80.0f, "power range");
    check_true(ina.manufacturer_id() == 0x5449, "manufacturer_id");
    check_true(ina.die_id()          == 0x2260, "die_id");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
