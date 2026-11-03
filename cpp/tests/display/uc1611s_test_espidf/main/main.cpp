#include <stdio.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <driver/i2c_master.h>
#include "I2CConnectionESPIDF.h"
#include "UC1611s.h"
#include "uc1611s_smoke.h"

static int passed = 0;
static int failed = 0;

static void report(const char* label, bool ok) {
    if (ok) { printf("PASS %s\n", label); passed++; }
    else    { printf("FAIL %s\n", label); failed++; }
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

    i2c_device_config_t cmd_cfg = {};
    cmd_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    cmd_cfg.device_address = 0x38;
    cmd_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t cmd_dev;
    i2c_master_bus_add_device(bus, &cmd_cfg, &cmd_dev);
    i2c_device_config_t data_cfg = cmd_cfg;
    data_cfg.device_address = 0x39;
    i2c_master_dev_handle_t data_dev;
    i2c_master_bus_add_device(bus, &data_cfg, &data_dev);

    I2CConnectionESPIDF cmdConn(cmd_dev);
    I2CConnectionESPIDF dataConn(data_dev);
    static UC1611sFull chip(cmdConn, dataConn);
    uc1611sChecks(chip, report);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
