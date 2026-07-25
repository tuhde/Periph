#include <stdio.h>
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
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    check_near(ina.voltage(0), 0.0f, 40.0f, "ch0 voltage range");
    check_near(ina.current(0),-2.0f,  2.0f, "ch0 current range");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
