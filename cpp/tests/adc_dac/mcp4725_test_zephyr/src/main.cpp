#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
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
    I2CTransportZephyr transport(i2c_dev, MCP4725_ADDR);
    MCP4725Full dac(transport);

    dac.set_voltage(0.5f);
    check_true("set_voltage(0.5) accepted", true);

    dac.set_raw(2048);
    check_true("set_raw(2048) accepted", true);

    dac.set_voltage_eeprom(0.5f);
    check_true("set_voltage_eeprom(0.5) accepted", true);

    dac.set_raw_eeprom(2048);
    check_true("set_raw_eeprom(2048) accepted", true);

    MCP4725Full::ReadResult state = dac.read();
    check_true("read returns code", state.code <= 4095);
    check_true("read returns eeprom_code", state.eeprom_code <= 4095);
    check_true("read returns voltage_fraction", state.voltage_fraction >= 0.0f && state.voltage_fraction <= 1.0f);

    dac.set_power_down(MCP4725Full::PD_NORMAL);
    check_true("set_power_down(NORMAL) accepted", true);

    dac.set_power_down(MCP4725Full::PD_1K_GND);
    check_true("set_power_down(1K) accepted", true);

    dac.set_power_down(MCP4725Full::PD_100K_GND);
    check_true("set_power_down(100K) accepted", true);

    dac.set_power_down(MCP4725Full::PD_500K_GND);
    check_true("set_power_down(500K) accepted", true);

    dac.wake_up();
    check_true("wake_up accepted", true);

    dac.reset();
    check_true("reset accepted", true);

    bool ready = dac.is_eeprom_ready();
    check_true("is_eeprom_ready returns bool", true);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}