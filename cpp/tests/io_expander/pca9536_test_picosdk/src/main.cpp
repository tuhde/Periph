#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "PCA9536.h"

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

#define CHK(c, l) check_true((c), l)

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x41);
    PCA9536Minimal chip(connection);

    CHK(chip._direction == 0xFF, "init_config");
    CHK(chip._shadow == 0xFF, "init_shadow");

    chip.write_port(0, 0x05);
    CHK((chip._shadow & 0x0F) == 0x05, "write_port_shadow");
    chip.write_port(0, 0x0F);
    uint8_t port_val = chip.read_port();
    CHK(port_val <= 0x0F, "read_port_range");

    auto p3 = chip.pin(3);
    p3.mode(OUTPUT);
    p3.low();
    CHK((chip._shadow & 0x08) == 0x00, "pin3_off");
    p3.high();
    CHK((chip._shadow & 0x08) == 0x08, "pin3_on");
    p3.toggle();
    CHK((chip._shadow & 0x08) == 0x00, "pin3_toggle");
    CHK(!p3.mode(INPUT_PULLUP), "pull_up_rejected");

    PCA9536Full full(connection);
    CHK(full._direction == 0xFF, "full_init_config");

    // Loopback: jumper P0-P1 to P2-P3 (Pn <-> Pn+2).
    full.set_direction(0x0C);
    CHK((full.read_register(0x03) & 0x0F) == 0x0C, "config_readback");
    full.write_port(0, 0x01);
    CHK(((full.read_port() >> 2) & 0x03) == 0x01, "loopback_0x1");
    full.write_port(0, 0x02);
    CHK(((full.read_port() >> 2) & 0x03) == 0x02, "loopback_0x2");
    full.write_port(0, 0x00);
    CHK(((full.read_port() >> 2) & 0x03) == 0x00, "loopback_0x0");

    full.configure_polarity(0x0C);
    CHK((full.read_register(0x02) & 0x0F) == 0x0C, "polarity_readback");
    CHK(((full.read_port() >> 2) & 0x03) == 0x03, "polarity_inverted");
    full.configure_polarity(0x00);

    full.pollChanges();
    full.write_port(0, 0x01);
    CHK(full.pollChanges() == 0x04, "poll_changes_changed");
    full.write_port(0, 0x00);

    full.reset();
    CHK((full.read_register(0x03) & 0x0F) == 0x0F, "reset_config");
    CHK((full.read_register(0x01) & 0x0F) == 0x0F, "reset_output");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
