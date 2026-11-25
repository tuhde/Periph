#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Pca9685.h"

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
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
    Pca9685Minimal pwm(connection);
    Pca9685Full full(connection);

    check_true(Pca9685Minimal::CHANNELS == 16, "constants");

    check_true(pwm.set_duty(0, 0.5f) && pwm.set_duty(15, 0.0f) && pwm.set_duty(1, 1.0f), "set_duty");
    check_true(!pwm.set_duty(16, 0.5f) && !pwm.set_duty(0, 1.5f), "set_duty_range");

    uint16_t on = 0, off = 0;
    check_true(full.set_pwm(2, 409, 1228) && !full.set_pwm(2, 5, 5), "set_pwm");
    check_true(full.get_pwm(2, on, off) && on == 409 && off == 1228, "get_pwm");
    check_true(full.set_full_on(3) && full.set_full_off(3), "full_on_off");
    check_true(full.set_all_duty(0.1f) && full.set_all_pwm(0, 2048) && full.set_all_duty(0.0f), "all");
    check_true(full.set_frequency(50.0f), "set_frequency");
    float hz = full.get_frequency();
    check_true(hz > 49.9f && hz < 50.1f, "get_frequency");
    check_true(full.set_pulse_width(15, 0.0015f), "pulse_width");
    full.sleep();
    full.wake();
    full.restart();
    check_true(true, "sleep_wake");
    check_true(full.set_output_mode(false, false, Pca9685Full::OFF_LOW), "output_mode");
    full.set_update_on_ack(false);
    check_true(full.set_allcall(false, 0x70), "allcall");
    check_true(full.set_subaddress(1, 0x71, false), "subaddress");
    uint8_t mode1 = 0xFF, mode2 = 0;
    full.read_mode(mode1, mode2);
    check_true((mode1 & 0x10) == 0 && (mode2 & 0x04) == 0x04, "read_mode");
    full.reset();
    check_true(true, "reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
