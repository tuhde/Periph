#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include "I2CTransportZephyr.h"
#include "PCF8574.h"

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { printk("PASS %s\n", label); passed++; }
    else { printk("FAIL %s: got 0x%02X, expected 0x%02X\n", label, got, expected); failed++; }
}

static void check_true(const char* label, bool condition) {
    if (condition) { printk("PASS %s\n", label); passed++; }
    else           { printk("FAIL %s\n", label); failed++; }
}

int main() {
    const struct device* i2c_dev = DEVICE_DT_GET(DT_NODELABEL(i2c0));
    I2CTransportZephyr transport(i2c_dev, 0x20);
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

    uint8_t v = p0.read();
    check_true("pin_read_range", v <= 1);

    PCF8574Full::IOExpanderPin p4 = chip.pin(4);
    p4.mode(INPUT);
    check_eq("input_shadow_bit4", (chip._shadow >> 4) & 1, 0x01);

    uint8_t changed = chip.clear_interrupt();
    check_true("clear_interrupt_range", changed <= 0xFF);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return 0;
}
