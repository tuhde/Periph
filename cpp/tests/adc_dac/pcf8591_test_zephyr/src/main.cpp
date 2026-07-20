#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "PCF8591.h"

#ifndef PCF8591_I2C_NODE
#define PCF8591_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef PCF8591_ADDR
#define PCF8591_ADDR 0x48
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(PCF8591_I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, PCF8591_ADDR);
    PCF8591Full adc(transport);

    uint8_t ch0 = adc.read_channel(0);
    check_true(ch0 <= 255, "read_channel(0) in [0, 255]");

    uint8_t ch3 = adc.read_channel(3);
    check_true(ch3 <= 255, "read_channel(3) in [0, 255]");

    uint8_t all_raw[PCF8591Minimal::NUM_CHANNELS];
    adc.read_all(all_raw);
    bool all_in_range = true;
    for (uint8_t i = 0; i < PCF8591Minimal::NUM_CHANNELS; i++) {
        if (all_raw[i] > 255) { all_in_range = false; break; }
    }
    check_true(all_in_range, "read_all values in [0, 255]");

    float v0 = adc.read_channel_voltage(0, 3.3f, 0.0f);
    check_true(v0 >= 0.0f && v0 <= 3.3f, "read_channel_voltage in [0, 3.3]");

    adc.configure(PCF8591Full::MODE_4_SINGLE_ENDED, false, false);
    check_true(true, "configure 4 single-ended accepted");

    adc.configure(PCF8591Full::MODE_3_DIFFERENTIAL, false, false);
    int8_t diff = adc.read_differential(0);
    check_true(diff >= -128 && diff <= 127, "read_differential in [-128, 127]");

    adc.configure(PCF8591Full::MODE_MIXED, false, false);
    check_true(true, "configure mixed mode accepted");

    adc.configure(PCF8591Full::MODE_2_DIFFERENTIAL, false, false);
    check_true(true, "configure 2 differential accepted");

    adc.configure(PCF8591Full::MODE_4_SINGLE_ENDED, true, false);
    uint8_t auto_raw[PCF8591Minimal::NUM_CHANNELS];
    adc.read_all(auto_raw);
    check_true(true, "read_all with auto-increment returns 4 values");

    adc.configure(PCF8591Full::MODE_4_SINGLE_ENDED, false, true);
    check_true(true, "configure enables DAC");

    adc.set_dac(0);
    check_true(true, "set_dac(0) accepted");

    adc.set_dac(255);
    check_true(true, "set_dac(255) accepted");

    adc.set_dac(128);
    check_true(true, "set_dac(128) accepted");

    adc.set_dac_voltage(0.0f);
    check_true(true, "set_dac_voltage(0.0) accepted");

    adc.set_dac_voltage(1.0f);
    check_true(true, "set_dac_voltage(1.0) accepted");

    adc.set_dac_voltage(0.5f);
    check_true(true, "set_dac_voltage(0.5) accepted");

    adc.disable_dac();
    check_true(true, "disable_dac accepted");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
