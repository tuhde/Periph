// ESP-IDF test for DS3502.
// Mirrors the Zephyr test for DS3502; prints PASS/FAIL and exits.

#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "DS3502.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
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
    dev_cfg.device_address = DS3502Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    DS3502Minimal pot(connection, 15.0f, 0.0f);             // Create DS3502 driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    pot.setWiper(100);                                      // Set wiper position, (code 0–127) → void
    check_true(pot.getWiper() == 100, "wiper_roundtrip");   // Read wiper position, () → uint8_t
    pot.setWiper(300);
    check_true(pot.getWiper() == 127, "wiper_clamps_127");
    pot.setVoltage(6.0f);                                   // Set RW output voltage, (volts V) → void
    check_true(fabsf(pot.getVoltage() - 6.0f) < 0.1f, "set_voltage"); // Read unloaded RW voltage, () → float V

    DS3502Full full(connection, 15.0f, 0.0f);               // Create DS3502 Full driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    check_true(!full.getMode(), "init_mode_sram");          // Read MODE, () → bool
    full.setRatio(0.5f);                                    // Set divider ratio, (ratio 0.0–1.0) → void
    check_true(full.getWiper() == 64 && fabsf(full.getRatio() - 0.5f) < 0.01f, "ratio_half"); // Read divider ratio, () → float
    full.setWiper(100);
    check_true(fabsf(full.getResistance('L') + full.getResistance('H') - 10000.0f) < 1.0f, "resistance_nominal"); // Read wiper resistance, (terminal='L') → float Ω
    check_true(full.increment(5) == 105 && full.getWiper() == 105, "increment"); // Step the wiper, (steps=1) → uint8_t
    check_true(full.increment(-500) == 0, "increment_clamps");
    full.setMode(true);                                     // Select EEPROM-backed writes, (eeprom bool) → void
    check_true(full.getMode(), "set_mode_eeprom");
    full.setMode(false);
    check_true(!full.getMode(), "set_mode_sram");
    check_true(full.waitReady(), "wait_ready");             // Acknowledge polling, (timeoutMs=30) → bool
    // storeWiper is not exercised: it consumes one of the EEPROM's limited write cycles.

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
