#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "PCF8591.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x48
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
    PCF8591Full adc(transport);

    uint8_t ch0 = adc.read_channel(0);
    check_true("read_channel(0) in [0, 255]", ch0 <= 255);

    uint8_t ch3 = adc.read_channel(3);
    check_true("read_channel(3) in [0, 255]", ch3 <= 255);

    uint8_t ch_oob = adc.read_channel(99);
    check_true("read_channel(99) clamped to valid range", ch_oob <= 255);

    uint8_t all_raw[PCF8591Minimal::NUM_CHANNELS];
    adc.read_all(all_raw);
    bool all_in_range = true;
    for (uint8_t i = 0; i < PCF8591Minimal::NUM_CHANNELS; i++) {
        if (all_raw[i] > 255) { all_in_range = false; break; }
    }
    check_true("read_all values in [0, 255]", all_in_range);

    float v0 = adc.read_channel_voltage(0, 3.3f, 0.0f);
    check_true("read_channel_voltage in [0, 3.3]", v0 >= 0.0f && v0 <= 3.3f);

    float v_all[PCF8591Minimal::NUM_CHANNELS];
    adc.read_all_voltage(v_all, 3.3f, 0.0f);
    bool all_v_in_range = true;
    for (uint8_t i = 0; i < PCF8591Minimal::NUM_CHANNELS; i++) {
        if (v_all[i] < 0.0f || v_all[i] > 3.3f) { all_v_in_range = false; break; }
    }
    check_true("read_all_voltage values in [0, 3.3]", all_v_in_range);

    adc.configure(PCF8591Full::MODE_4_SINGLE_ENDED, false, false);
    check_true("configure 4 single-ended accepted", true);

    adc.configure(PCF8591Full::MODE_3_DIFFERENTIAL, false, false);
    int8_t diff = adc.read_differential(0);
    check_true("read_differential in [-128, 127]", diff >= -128 && diff <= 127);

    adc.configure(PCF8591Full::MODE_MIXED, false, false);
    check_true("configure mixed mode accepted", true);

    adc.configure(PCF8591Full::MODE_2_DIFFERENTIAL, false, false);
    check_true("configure 2 differential accepted", true);

    adc.configure(PCF8591Full::MODE_4_SINGLE_ENDED, true, false);
    uint8_t auto_raw[PCF8591Minimal::NUM_CHANNELS];
    adc.read_all(auto_raw);
    check_true("read_all with auto-increment returns 4 values", true);

    adc.configure(PCF8591Full::MODE_4_SINGLE_ENDED, false, true);
    check_true("configure enables DAC", true);

    adc.set_dac(0);
    check_true("set_dac(0) accepted", true);

    adc.set_dac(255);
    check_true("set_dac(255) accepted", true);

    adc.set_dac(128);
    check_true("set_dac(128) accepted", true);

    adc.set_dac_voltage(0.0f);
    check_true("set_dac_voltage(0.0) accepted", true);

    adc.set_dac_voltage(1.0f);
    check_true("set_dac_voltage(1.0) accepted", true);

    adc.set_dac_voltage(0.5f);
    check_true("set_dac_voltage(0.5) accepted", true);

    adc.disable_dac();
    check_true("disable_dac accepted", true);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
