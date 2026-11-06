#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x20
#endif

#include <stdio.h>
#include "I2CConnectionLinux.h"
#include "MCP23008.h"

static int passed = 0, failed = 0;

static void check_eq(const char* label, int got, int expected) {
    if (got == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: got %02X want %02X\n", label, got, expected); failed++; }
}

#define CHK(c, l) check_eq(l, (c) ? 1 : 0, 1)

int main() {
    I2CConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);
    MCP23008Minimal mcp(connection);

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
