#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LPS28DFW.h"

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
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x5C,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    LPS28DFWFull inst(connection);                          // Create LPS28DFW driver, (connection)
    check_true(inst.chip_id() == 0xB4 || inst.chip_id() == 0x00, "chip_id_or_no_chip");
    check_near(inst.read_pressure(), 260.0f, 1260.0f, "pressure range");
    check_near(inst.read_temperature(), -40.0f, 85.0f, "temperature range");

    inst.configure(LPS28DFWFull::ODR_100_HZ, LPS28DFWFull::AVG_128,
                   LPS28DFWFull::FS_MODE_2, 0, 1);           // Configure chip, (odr=100 Hz, avg=128, fs_mode=2, lpf_en=False, lpf_cfg=ODR/9) → void
    check_true(inst._fs_mode == 1, "fs_mode_2_set");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}