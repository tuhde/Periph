#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "PCA6408A.h"

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
    PCA6408AMinimal chip(connection);

    CHK(chip._direction == 0xFF, "init_config");
    CHK(chip._shadow == 0xFF, "init_shadow");

    chip.write_port(0, 0x55);
    CHK(chip._shadow == 0x55, "write_port_shadow");
    chip.write_port(0, 0xFF);
    uint8_t port_val = chip.read_port();
    CHK(port_val <= 0xFF, "read_port_range");

    auto p7 = chip.pin(7);
    p7.mode(OUTPUT);
    p7.low();
    CHK((chip._shadow & 0x80) == 0x00, "pin7_off");
    p7.high();
    CHK((chip._shadow & 0x80) == 0x80, "pin7_on");
    p7.toggle();
    CHK((chip._shadow & 0x80) == 0x00, "pin7_toggle");
    CHK(!p7.mode(INPUT_PULLUP), "pull_up_rejected");

    PCA6408AFull full(connection);
    CHK(full._direction == 0xFF, "full_init_config");

    // Loopback: jumper P0-P3 to P4-P7 (Pn <-> Pn+4).
    full.set_direction(0xF0);
    CHK(full.read_register(0x03) == 0xF0, "config_readback");
    full.write_port(0, 0x05);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x05, "loopback_0x5");
    full.write_port(0, 0x0A);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x0A, "loopback_0xA");
    full.write_port(0, 0x00);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x00, "loopback_0x0");

    full.configure_polarity(0xF0);
    CHK(full.read_register(0x02) == 0xF0, "polarity_readback");
    CHK(((full.read_port() >> 4) & 0x0F) == 0x0F, "polarity_inverted");
    full.configure_polarity(0x00);

    full.pollInterrupt();
    full.write_port(0, 0x01);
    CHK(full.pollInterrupt() == 0x10, "poll_interrupt_changed");
    full.write_port(0, 0x00);

    full.reset();
    CHK(full.read_register(0x03) == 0xFF, "reset_config");
    CHK(full.read_register(0x01) == 0xFF, "reset_output");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
