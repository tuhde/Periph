// Auto-generated ESP-IDF test for AD5248.
// Mirrors the Zephyr test for AD5248; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MCP4XXX.h"
#include <stdlib.h>

// Part under test; wire the board to this part and its address (see the address in the connection setup).
#ifndef TEST_PART
#define TEST_PART MCP4XXXPart::MCP4532
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_near(float val, float lo, float hi, const char *label) {
    if (val >= lo && val <= hi) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: %.4f not in [%.4f, %.4f]\n",
                  label, (double)val, (double)lo, (double)hi); failed++; }
}

static void check_eq_u8(uint8_t val, uint8_t expected, const char *label) {
    if (val == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: 0x%02X != 0x%02X\n", label, val, expected); failed++; }
}


extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x58;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
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

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
