#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "PCA6416A.h"

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
    I2CConnectionPicoSDK connection(i2c0, 0x20);
    PCA6416AMinimal chip(connection);

    CHK(chip._direction == 0xFFFF, "init_config");
    CHK(chip._shadow == 0xFFFF, "init_shadow");

    chip.write_port(0, 0x55);
    chip.write_port(1, 0xAA);
    CHK(chip._shadow == 0xAA55, "write_port_shadow");
    chip.write_port(0, 0xFF);
    chip.write_port(1, 0xFF);
    uint8_t port_val = chip.read_port(1);
    CHK(port_val <= 0xFF, "read_port_range");

    auto p15 = chip.pin(15);
    p15.mode(OUTPUT);
    p15.low();
    CHK((chip._shadow & 0x8000) == 0x0000, "pin15_off");
    p15.high();
    CHK((chip._shadow & 0x8000) == 0x8000, "pin15_on");
    p15.toggle();
    CHK((chip._shadow & 0x8000) == 0x0000, "pin15_toggle");
    CHK(!p15.mode(INPUT_PULLUP), "pull_up_rejected");

    PCA6416AFull full(connection);
    CHK(full._direction == 0xFFFF, "full_init_config");

    // Loopback: jumper port 0 to port 1 (P0_n <-> P1_n).
    full.set_direction(0xFF00);
    CHK(full.read_register(0x06) == 0x00, "config0_readback");
    CHK(full.read_register(0x07) == 0xFF, "config1_readback");
    full.write_port(0, 0x5A);
    CHK(full.read_port(1) == 0x5A, "loopback_0x5A");
    full.write_port(0, 0xA5);
    CHK(full.read_port(1) == 0xA5, "loopback_0xA5");
    full.write_all(0x0000);
    CHK(full.read_port(1) == 0x00, "loopback_0x00");
    full.write_all(0x00FF);
    CHK(full.read_all() == 0xFFFF, "read_all_pair");
    full.write_all(0x0000);

    full.configure_polarity(0xFF00);
    CHK(full.read_register(0x05) == 0xFF, "polarity1_readback");
    CHK(full.read_port(1) == 0xFF, "polarity_inverted");
    full.configure_polarity(0x0000);

    full.pollInterrupt();
    full.write_port(0, 0x01);
    CHK(full.pollInterrupt() == 0x0100, "poll_interrupt_changed");
    full.write_port(0, 0x00);

    full.reset();
    CHK(full.read_register(0x06) == 0xFF, "reset_config0");
    CHK(full.read_register(0x07) == 0xFF, "reset_config1");
    CHK(full.read_register(0x02) == 0xFF, "reset_output0");
    CHK(full.read_register(0x03) == 0xFF, "reset_output1");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
