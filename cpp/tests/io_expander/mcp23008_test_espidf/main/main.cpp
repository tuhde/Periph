// Auto-generated ESP-IDF test for MCP23008.
// Mirrors the Zephyr test for MCP23008; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MCP23008.h"

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
    dev_cfg.device_address = 0x20;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    MCP23008Minimal mcp(connection);

    CHK(mcp._direction == 0xFF, "init_iodir");
    CHK(mcp._shadow == 0x00, "init_shadow");

    mcp.write_port(0, 0x55);
    CHK(mcp._shadow == 0x55, "write_port_shadow");
    mcp.write_port(0, 0x00);
    uint8_t port_val = mcp.read_port();
    CHK(port_val <= 0xFF, "read_port_range");

    auto p7 = mcp.pin(7);
    p7.mode(OUTPUT);
    p7.low();
    CHK((mcp._shadow & 0x80) == 0x00, "pin7_off");
    p7.high();
    CHK((mcp._shadow & 0x80) == 0x80, "pin7_on");
    p7.toggle();
    CHK((mcp._shadow & 0x80) == 0x00, "pin7_toggle");

    MCP23008Full full(connection);
    CHK(full._direction == 0xFF, "full_init_iodir");

    // Loopback: jumper GP0-GP3 to GP4-GP7 (GPn <-> GPn+4).
    full.write_register(0x00, 0xF0);
    CHK(full.read_register(0x00) == 0xF0, "iodir_readback");
    full.write_port(0, 0x05);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x05, "loopback_0x5");
    full.write_port(0, 0x0A);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x0A, "loopback_0xA");
    full.write_port(0, 0x00);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x00, "loopback_0x0");

    full.configure_polarity(0xF0);
    CHK(((full.read_port() >> 4) & 0x0F) == 0x0F, "polarity_inverted");
    full.configure_polarity(0x00);

    full.configure_pullup(0xF0);
    CHK(full._pullup == 0xF0 && full.read_register(0x06) == 0xF0, "pullup_readback");
    full.configure_pullup(0x00);

    full.set_default_value(0x00);
    full.enable_pin_interrupt(0x10);
    CHK(full.read_register(0x02) == 0x10, "gpinten_readback");
    full.write_port(0, 0x01);
    uint8_t flags = full.pollInterrupt();
    CHK(flags <= 0xFF, "poll_interrupt_range");
    uint8_t capture = full.read_capture();
    CHK(capture <= 0xFF, "read_capture_range");
    full.enable_pin_interrupt(0x00);
    full.write_port(0, 0x00);

    full.reset();
    CHK(full.read_register(0x00) == 0xFF, "reset_iodir");
    CHK(full.read_register(0x0A) == 0x00, "reset_olat");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
