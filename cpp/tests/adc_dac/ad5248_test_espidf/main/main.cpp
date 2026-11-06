// Auto-generated ESP-IDF test for AD5248.
// Mirrors the Zephyr test for AD5248; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AD5248.h"

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
    dev_cfg.device_address = 0x2C;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AD5248Full pot(connection);

    pot.reset();
    pot.set_wiper(0, 100);
    check_true(pot.get_wiper(0) == 100, "set/get wiper ch0");
    pot.set_wiper(1, 200);
    check_true(pot.get_wiper(1) == 200, "set/get wiper ch1");
    check_true(pot.get_wiper(0) == 100, "ch0 unchanged by ch1 write");
    pot.set_resistance(0, 5320.0f);
    check_true(pot.get_wiper(0) == 128, "set_resistance midscale code");
    check_true(pot.get_resistance(0) > 5319.0f && pot.get_resistance(0) < 5321.0f, "get_resistance ~5320");
    pot.set_both(10, 20);
    check_true(pot.get_wiper(0) == 10, "set_both ch0");
    check_true(pot.get_wiper(1) == 20, "set_both ch1");
    pot.set_ratio(0, 0.5f);
    check_true(pot.get_ratio(0) > 0.49f && pot.get_ratio(0) < 0.51f, "get_ratio ~0.5");
    pot.set_wiper(0, 250);
    check_true(pot.increment(0, 10) == 255, "increment clamps");
    pot.set_wiper(1, 77);
    pot.shutdown(1);
    check_true(pot.is_shutdown(1), "is_shutdown");
    pot.resume(1);
    check_true(pot.get_wiper(1) == 77, "RDAC kept across shutdown");
    pot.reset();
    check_true(pot.get_wiper(0) == 0x80, "reset ch0");
    check_true(pot.get_wiper(1) == 0x80, "reset ch1");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
