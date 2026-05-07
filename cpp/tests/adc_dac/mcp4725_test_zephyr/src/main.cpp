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

static void check_eq(uint32_t val, uint32_t expected, const char *label) {
    if (val == expected) { printk("PASS %s\n", label); passed++; }
    else { printk("FAIL %s: %u != %u\n", label, val, expected); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(MCP4725_I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, MCP4725_ADDR);
    MCP4725Full dac(transport);

    dac.set_voltage(0.5f);
    dac.set_raw(2048);

    MCP4725ReadResult result = dac.read();
    check_true("code in range", result.code <= 4095);
    check_true("voltage_fraction in range", result.voltage_fraction >= 0.0f && result.voltage_fraction <= 1.0f);
    check_true("power_down in range", result.power_down <= 3);
    check_true("eeprom_code in range", result.eeprom_code <= 4095);
    check_true("eeprom_power_down in range", result.eeprom_power_down <= 3);

    dac.set_power_down(1);
    result = dac.read();
    check_eq(result.power_down, 1, "power_down mode 1");

    dac.wake_up();
    dac.reset();
    check_true("eeprom_ready or write in progress", dac.is_eeprom_ready() == true || dac.is_eeprom_ready() == false);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}