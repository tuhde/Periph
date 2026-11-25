// Auto-generated ESP-IDF test for PCA9539.
// Mirrors the Zephyr test for PCA9539; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "PCA9539.h"

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
    dev_cfg.device_address = 0x74;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    PCA9539Minimal chip(connection);

    CHK(chip._direction == 0xFFFF, "init_config");
    CHK(chip._shadow == 0xFFFF, "init_shadow");

    chip.write_port(0, 0x55);
    chip.write_port(1, 0xAA);
    CHK(chip._shadow == 0xAA55, "write_port_shadow");
    chip.write_port(0, 0xFF);
    chip.write_port(1, 0xFF);
    uint8_t port_val = chip.read_port(1);
    CHK(port_val <= 0xFF, "read_port_range");

    auto p15 = chip.pin(15);
    p15.mode(OUTPUT);
    p15.low();
    CHK((chip._shadow & 0x8000) == 0x0000, "pin15_off");
    p15.high();
    CHK((chip._shadow & 0x8000) == 0x8000, "pin15_on");
    p15.toggle();
    CHK((chip._shadow & 0x8000) == 0x0000, "pin15_toggle");
    CHK(!p15.mode(INPUT_PULLUP), "pull_up_rejected");

    PCA9539Full full(connection);
    CHK(full._direction == 0xFFFF, "full_init_config");

    // Loopback: jumper port 0 to port 1 (P0_n <-> P1_n).
    full.set_direction(0xFF00);
    CHK(full.read_register(0x06) == 0x00, "config0_readback");
    CHK(full.read_register(0x07) == 0xFF, "config1_readback");
    full.write_port(0, 0x5A);
    CHK(full.read_port(1) == 0x5A, "loopback_0x5A");
    full.write_port(0, 0xA5);
    CHK(full.read_port(1) == 0xA5, "loopback_0xA5");
    full.write_all(0x0000);
    CHK(full.read_port(1) == 0x00, "loopback_0x00");
    full.write_all(0x00FF);
    CHK(full.read_all() == 0xFFFF, "read_all_pair");
    full.write_all(0x0000);

    full.configure_polarity(0xFF00);
    CHK(full.read_register(0x05) == 0xFF, "polarity1_readback");
    CHK(full.read_port(1) == 0xFF, "polarity_inverted");
    full.configure_polarity(0x0000);

    full.pollInterrupt();
    full.write_port(0, 0x01);
    CHK(full.pollInterrupt() == 0x0100, "poll_interrupt_changed");
    full.write_port(0, 0x00);

    full.reset();
    CHK(full.read_register(0x06) == 0xFF, "reset_config0");
    CHK(full.read_register(0x07) == 0xFF, "reset_config1");
    CHK(full.read_register(0x02) == 0xFF, "reset_output0");
    CHK(full.read_register(0x03) == 0xFF, "reset_output1");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
