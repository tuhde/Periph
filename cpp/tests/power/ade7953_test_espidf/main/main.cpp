#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADE7953.h"

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool cond) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else      { printf("FAIL %s\n", label); failed++; }
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
    dev_cfg.device_address = 0x38;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADE7953Full ade(connection, 251.0f, 30.0f);

    check_true("voltage non-negative", ade.voltage() >= 0.0f);
    check_true("current non-negative", ade.current() >= 0.0f);
    check_true("activePower finite",   ade.activePower() > -1.0e6f);
    check_true("activeEnergy finite",  ade.activeEnergy() > -1000.0f);
    check_true("linePeriod positive",  ade.linePeriod() > 0.0f);

    ade.reset();
    check_true("voltage after reset", ade.voltage() >= 0.0f);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}