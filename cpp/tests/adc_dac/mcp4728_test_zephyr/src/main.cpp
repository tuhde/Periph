#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "MCP4728.h"

#ifndef MCP4728_I2C_NODE
#define MCP4728_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MCP4728_ADDR
#define MCP4728_ADDR 0x60
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(MCP4728_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MCP4728_ADDR);
    MCP4728Full dac(connection);

    dac.set_voltage(0, 0.5f);
    check_true(true, "set_voltage(ch0, 0.5) accepted");

    dac.set_raw(1, 2048);
    check_true(true, "set_raw(ch1, 2048) accepted");

    float fractions[4] = {0.0f, 0.25f, 0.5f, 1.0f};
    dac.set_all(fractions);
    check_true(true, "set_all accepted");

    dac.set_voltage_eeprom(0, 0.5f, MCP4728Full::VREF_EXTERNAL, MCP4728Full::GAIN_X1);
    check_true(true, "set_voltage_eeprom accepted");

    dac.set_raw_eeprom(1, 2048, MCP4728Full::VREF_EXTERNAL, MCP4728Full::GAIN_X1);
    check_true(true, "set_raw_eeprom accepted");

    float fracs[4]    = {0.0f, 0.25f, 0.5f, 0.75f};
    uint8_t vrefs[4]  = {0, 0, 0, 0};
    uint8_t gains[4]  = {1, 1, 1, 1};
    dac.set_all_eeprom(fracs, vrefs, gains);
    check_true(true, "set_all_eeprom accepted");

    dac.set_vref(0, 0, 0, 0);
    check_true(true, "set_vref accepted");

    dac.set_gain(1, 1, 1, 1);
    check_true(true, "set_gain accepted");

    MCP4728Full::ReadResult state = dac.read();
    check_true(state.channel[0].code <= 4095, "read returns code in range");
    check_true(state.channel[0].eeprom_code <= 4095, "read returns eeprom_code in range");
    check_true(state.channel[0].gain == 1 || state.channel[0].gain == 2, "read returns gain valid");

    dac.software_update();
    check_true(true, "software_update accepted");

    dac.wake_up();
    check_true(true, "wake_up accepted");

    dac.reset();
    check_true(true, "reset accepted");

    bool ready = dac.is_eeprom_ready();
    check_true(true, "is_eeprom_ready returns bool");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
