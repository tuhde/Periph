#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "MCP23008.h"

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
    MCP23008Minimal mcp(connection);
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    CHK(mcp._direction == 0xFF, "init_iodir");
    CHK(mcp._shadow == 0x00, "init_shadow");

    mcp.write_port(0, 0x55);
    CHK(mcp._shadow == 0x55, "write_port_shadow");
    mcp.write_port(0, 0x00);
    uint8_t port_val = mcp.read_port();
    CHK(port_val <= 0xFF, "read_port_range");

    auto p7 = mcp.pin(7);
    p7.mode(OUTPUT);
    p7.low();
    CHK((mcp._shadow & 0x80) == 0x00, "pin7_off");
    p7.high();
    CHK((mcp._shadow & 0x80) == 0x80, "pin7_on");
    p7.toggle();
    CHK((mcp._shadow & 0x80) == 0x00, "pin7_toggle");

    MCP23008Full full(connection);
    CHK(full._direction == 0xFF, "full_init_iodir");

    // Loopback: jumper GP0-GP3 to GP4-GP7 (GPn <-> GPn+4).
    full.write_register(0x00, 0xF0);
    CHK(full.read_register(0x00) == 0xF0, "iodir_readback");
    full.write_port(0, 0x05);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x05, "loopback_0x5");
    full.write_port(0, 0x0A);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x0A, "loopback_0xA");
    full.write_port(0, 0x00);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x00, "loopback_0x0");

    full.configure_polarity(0xF0);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x0F, "polarity_inverted");
    full.configure_polarity(0x00);

    full.configure_pullup(0xF0);
    CHK(full._pullup == 0xF0 && full.read_register(0x06) == 0xF0, "pullup_readback");
    full.configure_pullup(0x00);

    full.set_default_value(0x00);
    full.enable_pin_interrupt(0x10);
    CHK(full.read_register(0x02) == 0x10, "gpinten_readback");
    full.write_port(0, 0x01);
    uint8_t flags = full.pollInterrupt();
    CHK(flags <= 0xFF, "poll_interrupt_range");
    uint8_t capture = full.read_capture();
    CHK(capture <= 0xFF, "read_capture_range");
    full.enable_pin_interrupt(0x00);
    full.write_port(0, 0x00);

    full.reset();
    CHK(full.read_register(0x00) == 0xFF, "reset_iodir");
    CHK(full.read_register(0x0A) == 0x00, "reset_olat");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
