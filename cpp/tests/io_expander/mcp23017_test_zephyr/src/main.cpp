#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "MCP23017.h"

#ifndef MCP23017_I2C_NODE
#define MCP23017_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MCP23017_ADDR
#define MCP23017_ADDR 0x20
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

static void check_eq(const char* label, int got, int expected) {
    if (got == expected) { printk("PASS %s\n", label); passed++; }
    else { printk("FAIL %s: got %02X want %02X\n", label, got, expected); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MCP23017_I2C_NODE);
    I2CTransportZephyr transport(dev, MCP23017_ADDR);
    MCP23017Minimal mcp(transport);

    check_eq("init_iodira", mcp._direction[0], 0x7F);
    check_eq("init_iodirb", mcp._direction[1], 0x7F);

    mcp.write_port(0, 0xAA);
    check_eq("write_port_shadow_a", mcp._shadow[0], 0xAA);

    auto p7 = mcp.pin(7);
    p7.mode(OUTPUT);
    p7.low();
    check_eq("pin7_off", mcp._shadow[0] & 0x80, 0x00);
    p7.on();
    check_eq("pin7_on", mcp._shadow[0] & 0x80, 0x80);

    MCP23017Full full(transport);
    full.configure_pullup(0, 0x3F);
    check_eq("pullup_a", full._pullup[0], 0x3F);

    full.configure_interrupt(0, -1, [](uint8_t mask) {}, "change", false);
    full.stop_interrupt(0);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}