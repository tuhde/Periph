#include <cstdio>
#include "I2CTransportLinux.h"
#include "PCF8574.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x20
#endif

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: got 0x%02X, expected 0x%02X\n", label, got, expected); failed++; }
}

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
    PCF8574Full chip(transport);

    check_eq("init_shadow", chip._shadow, 0xFF);

    uint8_t port = chip.read_port();
    check_true("read_port_range", port <= 0xFF);

    chip.write_port(0, 0xAA);
    check_eq("write_port_shadow", chip._shadow, 0xAA);
    chip.write_port(0, 0xFF);

    PCF8574Full::IOExpanderPin p0 = chip.pin(0);
    p0.mode(OUTPUT);
    p0.low();
    check_eq("pin_low_shadow", chip._shadow & 0x01, 0x00);
    p0.high();
    check_eq("pin_high_shadow", chip._shadow & 0x01, 0x01);
    p0.toggle();
    check_eq("pin_toggle_shadow", chip._shadow & 0x01, 0x00);
    p0.write(HIGH);
    check_eq("pin_write_high", chip._shadow & 0x01, 0x01);

    uint8_t v = p0.read();
    check_true("pin_read_range", v <= 1);

    PCF8574Full::IOExpanderPin p4 = chip.pin(4);
    p4.mode(INPUT);
    check_eq("input_shadow_bit4", (chip._shadow >> 4) & 1, 0x01);

    uint8_t changed = chip.clear_interrupt();
    check_true("clear_interrupt_range", changed <= 0xFF);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed ? 1 : 0;
}
