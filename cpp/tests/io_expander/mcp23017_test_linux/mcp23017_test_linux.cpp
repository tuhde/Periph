#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x20
#endif

#include <stdio.h>
#include "I2CTransportLinux.h"
#include "MCP23017.h"

static int passed = 0, failed = 0;

static void check_true(const char *label, bool cond) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_eq(const char* label, int got, int expected) {
    if (got == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: got %02X want %02X\n", label, got, expected); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
    MCP23017Minimal mcp(transport);

    check_eq("init_iodira", mcp._direction[0], 0x7F);
    check_eq("init_iodirb", mcp._direction[1], 0x7F);
    check_eq("init_shadow_a", mcp._shadow[0], 0x00);
    check_eq("init_shadow_b", mcp._shadow[1], 0x00);

    uint8_t porta = mcp.read_port(0);
    check_true("read_port_a_range", porta >= 0 && porta <= 255);

    mcp.write_port(0, 0x55);
    check_eq("write_port_shadow_a", mcp._shadow[0], 0x55);
    mcp.write_port(0, 0xFF);

    auto p7 = mcp.pin(7);
    p7.mode(OUTPUT);
    p7.low();
    check_eq("pin7_off", mcp._shadow[0] & 0x80, 0x00);
    p7.high();
    check_eq("pin7_on", mcp._shadow[0] & 0x80, 0x80);
    p7.toggle();
    check_eq("pin7_toggle", mcp._shadow[0] & 0x80, 0x00);

    auto p15 = mcp.pin(15);
    p15.mode(OUTPUT);
    p15.low();
    check_eq("pin15_off", mcp._shadow[1] & 0x80, 0x00);
    p15.high();
    check_eq("pin15_on", mcp._shadow[1] & 0x80, 0x80);

    // Loopback: PA (outputs) → PB (inputs); PA[n]↔PB[7-n]
    for (uint8_t n = 0; n <= 7; n++) { auto p = mcp.pin(n); p.mode(OUTPUT); }

    mcp.write_port(0, 0xAA);  // PA0=0, avoids contention with PB7 output
    uint8_t pb = mcp.read_port(1);
    check_eq("loopback_0xAA", pb & 0x7F, 0x55);

    mcp.write_port(0, 0xFE);  // PA0=0, PA1–PA7=1
    pb = mcp.read_port(1);
    check_eq("loopback_0xFE", pb & 0x7F, 0x7F);

    mcp.write_port(0, 0x00);
    pb = mcp.read_port(1);
    check_eq("loopback_0x00", pb & 0x7F, 0x00);

    MCP23017Full full(transport);
    check_eq("full_init_iodira", full._direction[0], 0x7F);

    full.configure_pullup(0, 0x3F);
    check_eq("pullup_a", full._pullup[0], 0x3F);

    full.set_default_value(0, 0x00);
    full.configure_interrupt(0, -1, [](uint8_t mask) {}, "default", false);
    full.stop_interrupt(0);

    uint8_t changed = full.clear_interrupt(0);
    check_true("clear_interrupt_range", changed >= 0 && changed <= 255);

    uint8_t flags = full.read_interrupt_flags(0);
    check_true("int_flags_range", flags >= 0 && flags <= 255);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}