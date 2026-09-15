// ESP-IDF test for BMP581. Mirrors the Arduino bmp581_test.ino using I2CConnectionESPIDF.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "BMP581.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
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
        .device_address  = 0x46,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    BMP581Full bmp(connection, /*spi=*/false);

    check_true(bmp.chip_id() == 0x50, "chip_id");
    bmp.configure(0x1C, BMP581Full::OSR_1X, BMP581Full::OSR_1X, true);
    check_true(bmp._odr == 0x1C && bmp._osr_p == 0 && bmp._osr_t == 0, "configure_state");
    bmp.set_mode(BMP581Full.MODE_NORMAL);
    bmp.set_iir_filter(BMP581Full::IIR_COEFF_3, BMP581Full::IIR_BYPASS);
    bmp.enable_drdy_interrupt(true);
    bmp.configure_fifo(BMP581Full::FIFO_BOTH, BMP581Full::FIFO_STREAM, 8);
    bmp.set_oor_threshold(110000.0f, 200.0f, 1);

    float alt = bmp.altitude();
    check_true(alt >= -500.0f && alt <= 9000.0f, "altitude_range");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}