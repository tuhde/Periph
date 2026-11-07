#include <stdlib.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "MCP4XXX.h"

#ifndef AD5248_I2C_NODE
#define AD5248_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef TEST_PART
#define TEST_PART MCP4XXXPart::MCP4532
#endif
#ifndef MCP4XXX_ADDR
#define MCP4XXX_ADDR 0x58
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(AD5248_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MCP4XXX_ADDR);
    MCP4XXXFull pot(connection, TEST_PART, 10000.0f, 75.0f);

    pot.init();
    int max = (int)pot.steps() - 1;

    // --- Wiper write / readback ---
    pot.set_wiper(100 % (max + 1), 0);
    check_true(pot.get_wiper(0) == 100 % (max + 1), "set/get wiper");
    pot.set_wiper(9999, 0);
    check_true(pot.get_wiper(0) == max, "clamps high");
    pot.set_wiper(-5, 0);
    check_true(pot.get_wiper(0) == 0, "clamps low");
    if (pot.channels() == 2) {
        pot.set_both(10, 20);
        check_true(pot.get_wiper(0) == 10, "set_both ch0");
        check_true(pot.get_wiper(1) == 20, "set_both ch1");
    }

    // --- Resistance and ratio ---
    pot.set_resistance(5075.0f, 0);
    check_true(abs((int)pot.get_wiper(0) - max / 2) <= 1, "set_resistance ~mid");
    check_true(pot.get_resistance(0) > 4975.0f && pot.get_resistance(0) < 5175.0f, "get_resistance ~5075");
    pot.set_ratio(0.5f, 0);
    check_true(pot.get_ratio(0) > 0.49f && pot.get_ratio(0) < 0.51f, "get_ratio ~0.5");
    if (pot.is_potentiometer()) {
        pot.set_wiper(0, 0);
        check_true(pot.get_resistance_wa(0) > 9975.0f && pot.get_resistance_wa(0) < 10175.0f, "R_WA at zero scale ~RAB");
    }

    // --- Increment / decrement (continuous) ---
    pot.set_wiper(max - 3, 0);
    check_true(pot.increment(10, 0) == max, "increment saturates");
    pot.set_wiper(3, 0);
    check_true(pot.decrement(10, 0) == 0, "decrement saturates");
    check_true(pot.increment(1, 0) == 1, "increment 1");

    // --- Shutdown / terminals ---
    pot.set_wiper(max / 4, 0);
    pot.shutdown(0);
    check_true(pot.is_shutdown(0), "is_shutdown");
    pot.resume(0);
    check_true(!pot.is_shutdown(0), "not shutdown");
    check_true(pot.get_wiper(0) == max / 4, "wiper kept across shutdown");
    pot.set_terminals(true, false, true, 0);
    bool ta, tw, tb;
    pot.get_terminals(0, ta, tw, tb);
    check_true(ta && !tw && tb, "terminals");
    pot.set_terminals(true, true, true, 0);
    check_true((pot.get_tcon() & 0xFF) == 0xFF, "tcon restored");

    // --- Non-volatile parts ---
    if (pot.is_nonvolatile()) {
        MCP4XXXStatus status;
        pot.read_status(status);
        check_true(!status.eeprom_busy, "status no busy");
        pot.set_wiper(max / 2, 0);
        check_true(pot.store_wiper(0) == max / 2, "store_wiper");
        check_true(pot.get_nv_wiper(0) == max / 2, "get_nv_wiper");
        pot.write_data(0, 0x155);
        check_true(pot.read_data(0) == 0x155, "data roundtrip");
    }

    // --- Reset ---
    pot.set_wiper(1, 0);
    pot.reset();
    check_true(pot.get_wiper(0) == max / 2, "reset wiper");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
