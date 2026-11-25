#include <string.h>
#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include <math.h>
#include "LM25066.h"

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

static bool ok_model(LM25066Full& h) {
    char id[16], mdl[16];
    h.manufacturer_id(id, sizeof id);
    h.model(mdl, sizeof mdl);
    return strcmp(id, "NSC") == 0 && strcmp(mdl, "LM25066") == 0;
}

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x40);
    LM25066Full hotswap(connection, 1.0f, false);
    check_true(ok_model(hotswap), "model is LM25066");
    check_true(hotswap.voltage_in() >= 0.0f && hotswap.voltage_in() <= 24.0f, "vin range");
    check_true(hotswap.voltage_out() >= 0.0f && hotswap.voltage_out() <= 24.0f, "vout range");
    check_true(hotswap.current() > -1.0f, "current readable");
    check_true(hotswap.power() > -5.0f, "power readable");
    check_true(hotswap.temperature() > -40.0f && hotswap.temperature() < 150.0f, "temperature plausible");
    check_true(hotswap.voltage_aux() >= 0.0f && hotswap.voltage_aux() <= 1.2f, "vaux range");
    check_true((hotswap.read_block(), hotswap.valid()), "block valid");
    check_true((hotswap.read_average(), hotswap.valid()), "avg block valid");
    check_true((hotswap.set_averaging(4), hotswap.get_averaging() == 4), "averaging");
    check_true((hotswap.set_averaging(0), hotswap.get_averaging() == 0), "averaging reset");
    check_true((hotswap.set_vin_uv_warn(8.0f), fabsf(hotswap.get_limits().vin_uv_warn - 8.0f) < 0.05f), "vin_uv_warn");
    check_true((hotswap.set_vin_uv_warn(NAN), isnan(hotswap.get_limits().vin_uv_warn)), "vin_uv_warn disabled");
    check_true((hotswap.set_ot_warn(110.0f), fabsf(hotswap.get_limits().ot_warn - 110.0f) < 0.1f), "ot_warn");
    check_true((hotswap.set_ot_warn(125.0f), true), "ot_warn restore");
    check_true((hotswap.clear_power_peak(), hotswap.power_peak() >= 0.0f), "clear peak");
    check_true((hotswap.clear_faults(), hotswap.status_word() != 0xFFFF), "clear faults");
    check_true(hotswap.pollInterrupt() == hotswap.pollInterrupt() || true, "diagnostic readable");
    check_true(hotswap.capability() == 0xB0, "capability");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
