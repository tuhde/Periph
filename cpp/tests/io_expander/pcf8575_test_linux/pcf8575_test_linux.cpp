#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x20
#endif

#include <cstdio>
#include <cstdlib>
#include <cstdint>

#ifndef OUTPUT
#define INPUT        0
#define OUTPUT       1
#define INPUT_PULLUP 2
#define HIGH         1
#define LOW          0
#define RISING       1
#define FALLING      2
#define CHANGE       3
#endif

#include "I2CTransportLinux.h"
#include "PCF8575.h"

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: got 0x%02X, expected 0x%02X\n", label, got, expected); failed++; }
}

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
    PCF8575Minimal chip(transport);

    check_eq("init_shadow_0", chip._shadow[0], 0xFF);
    check_eq("init_shadow_1", chip._shadow[1], 0xFF);

    uint8_t port0 = chip.read_port(0);
    uint8_t port1 = chip.read_port(1);
    check_true("read_port_0_range", port0 <= 0xFF);
    check_true("read_port_1_range", port1 <= 0xFF);

    chip.write_port(0, 0xAA);
    check_eq("write_port_0_shadow", chip._shadow[0], 0xAA);
    chip.write_port(1, 0x55);
    check_eq("write_port_1_shadow", chip._shadow[1], 0x55);
    chip.write_port(0, 0xFF);
    chip.write_port(1, 0xFF);

    PCF8575Minimal::IOExpanderPin p0 = chip.pin(0);
    p0.mode(OUTPUT);
    p0.low();
    check_eq("pin_low_shadow",  chip._shadow[0] & 0x01, 0x00);
    p0.high();
    check_eq("pin_high_shadow", chip._shadow[0] & 0x01, 0x01);
    p0.toggle();
    check_eq("pin_toggle_shadow", chip._shadow[0] & 0x01, 0x00);
    p0.write(HIGH);
    check_eq("pin_write_high_shadow", chip._shadow[0] & 0x01, 0x01);

    chip.write_port(0, 0xFF);
    chip.write_port(1, 0xFF);

    // Loopback: port 0 (outputs) → port 1 (inputs); P0x ↔ P1(7-x)
    chip.write_port(1, 0xFF);

    chip.write_port(0, 0xAA);
    check_eq("loopback_0xAA", chip.read_port(1), 0x55);

    chip.write_port(0, 0xF0);
    check_eq("loopback_0xF0", chip.read_port(1), 0x0F);

    chip.write_port(0, 0x00);
    check_eq("loopback_0x00", chip.read_port(1), 0x00);

    chip.write_port(0, 0xFF);
    chip.write_port(1, 0xFF);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed;
}
