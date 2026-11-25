// Auto-generated ESP-IDF test for PCA9536.
// Mirrors the Zephyr test for PCA9536; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCA9536.h"

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


#define CHK(c, l) check_true((c), l)

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
    dev_cfg.device_address = 0x41;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    PCA9536Minimal chip(connection);

    CHK(chip._direction == 0xFF, "init_config");
    CHK(chip._shadow == 0xFF, "init_shadow");

    chip.write_port(0, 0x05);
    CHK((chip._shadow & 0x0F) == 0x05, "write_port_shadow");
    chip.write_port(0, 0x0F);
    uint8_t port_val = chip.read_port();
    CHK(port_val <= 0x0F, "read_port_range");

    auto p3 = chip.pin(3);
    p3.mode(OUTPUT);
    p3.low();
    CHK((chip._shadow & 0x08) == 0x00, "pin3_off");
    p3.high();
    CHK((chip._shadow & 0x08) == 0x08, "pin3_on");
    p3.toggle();
    CHK((chip._shadow & 0x08) == 0x00, "pin3_toggle");
    CHK(!p3.mode(INPUT_PULLUP), "pull_up_rejected");

    PCA9536Full full(connection);
    CHK(full._direction == 0xFF, "full_init_config");

    // Loopback: jumper P0-P1 to P2-P3 (Pn <-> Pn+2).
    full.set_direction(0x0C);
    CHK((full.read_register(0x03) & 0x0F) == 0x0C, "config_readback");
    full.write_port(0, 0x01);
    CHK(((full.read_port() >> 2) & 0x03) == 0x01, "loopback_0x1");
    full.write_port(0, 0x02);
    CHK(((full.read_port() >> 2) & 0x03) == 0x02, "loopback_0x2");
    full.write_port(0, 0x00);
    CHK(((full.read_port() >> 2) & 0x03) == 0x00, "loopback_0x0");

    full.configure_polarity(0x0C);
    CHK((full.read_register(0x02) & 0x0F) == 0x0C, "polarity_readback");
    CHK(((full.read_port() >> 2) & 0x03) == 0x03, "polarity_inverted");
    full.configure_polarity(0x00);

    full.pollChanges();
    full.write_port(0, 0x01);
    CHK(full.pollChanges() == 0x04, "poll_changes_changed");
    full.write_port(0, 0x00);

    full.reset();
    CHK((full.read_register(0x03) & 0x0F) == 0x0F, "reset_config");
    CHK((full.read_register(0x01) & 0x0F) == 0x0F, "reset_output");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
