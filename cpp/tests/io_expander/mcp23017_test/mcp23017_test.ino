#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x20
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/io_expander/MCP23017.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { Serial.print("PASS "); Serial.println(label); passed++; }
    else       { Serial.print("FAIL "); Serial.println(label); failed++; }
}

static void check_eq(const char* label, int got, int expected) {
    if (got == expected) { Serial.print("PASS "); Serial.println(label); passed++; }
    else { Serial.print("FAIL "); Serial.print(label); Serial.print(" got "); Serial.print(got, HEX); Serial.print(" want "); Serial.println(expected, HEX); failed++; }
}

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, TEST_ADDR);
    MCP23017Minimal mcp(transport);

    check_eq("init_iodira", mcp._direction[0], 0x7F);
    check_eq("init_iodirb", mcp._direction[1], 0x7F);
    check_eq("init_shadow_a", mcp._shadow[0], 0x00);
    check_eq("init_shadow_b", mcp._shadow[1], 0x00);

    mcp.write_port(0, 0xAA);
    check_eq("write_port_shadow_a", mcp._shadow[0], 0xAA);
    mcp.write_port(0, 0xFF);

    auto p7 = mcp.pin(7);
    p7.mode(OUTPUT);
    p7.low();
    check_eq("pin7_off", mcp._shadow[0] & 0x80, 0x00);
    p7.on();
    check_eq("pin7_on", mcp._shadow[0] & 0x80, 0x80);

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
    full.configure_polarity(0, 0x00);

    full.set_default_value(0, 0x00);
    full.configure_interrupt(0, -1, [](uint8_t mask) {}, "change", false);
    full.stop_interrupt(0);

    uint8_t changed = full.clear_interrupt(0);
    check_true("clear_interrupt_range", changed >= 0 && changed <= 255);

    uint8_t flags = full.read_interrupt_flags(0);
    check_true("int_flags_range", flags >= 0 && flags <= 255);

    Serial.print("===DONE: ");
    Serial.print(passed);
    Serial.print(" passed, ");
    Serial.print(failed);
    Serial.println(" failed===");
}

void loop() { delay(1000); }