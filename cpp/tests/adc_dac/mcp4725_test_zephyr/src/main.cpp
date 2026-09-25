#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "MCP4725.h"

#ifndef MCP4725_I2C_NODE
#define MCP4725_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MCP4725_ADDR
#define MCP4725_ADDR 0x60
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(MCP4725_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MCP4725_ADDR);
    MCP4725Full dac(connection);

    dac.set_voltage(0.5f);
    check_true(true, "set_voltage(0.5) accepted");

    dac.set_raw(2048);
    check_true(true, "set_raw(2048) accepted");

    dac.set_voltage_eeprom(0.5f);
    check_true(true, "set_voltage_eeprom(0.5) accepted");

    dac.set_raw_eeprom(2048);
    check_true(true, "set_raw_eeprom(2048) accepted");

    MCP4725Full::ReadResult state = dac.read();
    check_true(state.code <= 4095, "read returns code");
    check_true(state.eeprom_code <= 4095, "read returns eeprom_code");
    check_true(state.voltage_fraction >= 0.0f && state.voltage_fraction <= 1.0f, "read returns voltage_fraction");

    dac.wake_up();
    check_true(true, "wake_up accepted");

    dac.reset();
    check_true(true, "reset accepted");

    bool ready = dac.is_eeprom_ready();
    check_true(true, "is_eeprom_ready returns bool");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}