// Auto-generated ESP-IDF test for LTC4151.
// Mirrors the Zephyr test for LTC4151; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LTC4151.h"

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
    dev_cfg.device_address = 0x6A;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    LTC4151Full ltc(connection);  // Create LTC4151 driver
    check_near(ltc.voltage(), 7.0f, 80.0f, "voltage range");
    check_near(ltc.current(), 0.0f, 4.096f, "current range");
    check_near(ltc.power(), 0.0f, 330.0f, "power range");
    check_near(ltc.sense_voltage(), 0.0f, 0.08192f, "sense voltage range");
    check_near(ltc.adin_voltage(), 0.0f, 2.048f, "adin range");
    LTC4151Full::Readings r;
    ltc.read_all(r);
    check_near(r.vin, 7.0f, 80.0f, "read_all vin");
    check_true(ltc.read_raw(LTC4151Full::VIN) > 0 && ltc.read_raw(LTC4151Full::VIN) < 4096, "read_raw vin");
    check_true(ltc.read_control() == 0x0C, "control default");
    check_true(!ltc.is_snapshot_mode(), "not snapshot mode");
    float s = 0;
    check_true(ltc.snapshot(LTC4151Full::VIN, s) && s >= 7.0f && s <= 80.0f, "snapshot vin");
    check_true(!ltc.is_snapshot_mode(), "back in continuous");
    ltc.set_stuck_bus_timer(false);
    check_true(ltc.read_control() == 0x08, "stuck-bus timer off");
    ltc.set_stuck_bus_timer(true);
    check_true(ltc.read_control() == 0x0C, "stuck-bus timer on");
    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
