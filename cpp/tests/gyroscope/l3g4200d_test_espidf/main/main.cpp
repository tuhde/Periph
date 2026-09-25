#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "L3G4200D.h"

static int passed = 0;
static int failed = 0;

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
    dev_cfg.device_address = 0x68;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    L3G4200DFull inst(connection, false);
    check_true(inst.who_am_i() == 0xD3, "who_am_i");

    float x, y, z;
    inst.angular_rate(x, y, z);
    check_true(x >= -50.0f && x <= 50.0f, "angular_rate_x_range");
    check_true(y >= -50.0f && y <= 50.0f, "angular_rate_y_range");
    check_true(z >= -50.0f && z <= 50.0f, "angular_rate_z_range");

    inst.configure(1, 0, 500);
    float x2, y2, z2;
    inst.angular_rate(x2, y2, z2);
    check_true(x2 >= -500.0f && x2 <= 500.0f, "configure_then_read_x");

    inst.set_full_scale(2000);
    float x3, y3, z3;
    inst.angular_rate(x3, y3, z3);
    check_true(x3 >= -2000.0f && x3 <= 2000.0f, "set_full_scale_2000");

    check_true(inst.status() <= 0xFF, "status_readable");
    check_true(inst.temperature() >= -50 && inst.temperature() <= 100, "temperature_range");

    inst.enable_fifo(2, 10);
    check_true(inst.fifo_samples() <= 31, "fifo_samples_in_range");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
