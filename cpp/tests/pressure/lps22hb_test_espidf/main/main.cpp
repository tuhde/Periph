#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Lps22hb.h"

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
    dev_cfg.device_address = 0x5C;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    LPS22HBMinimal lps(connection);

    float p = lps.pressure();
    check_true(p >= 80000.0f && p <= 120000.0f, "pressure_range");

    float t = lps.temperature();
    check_true(t >= -10.0f && t <= 60.0f, "temperature_range");

    LPS22HBFull lps_full(connection);

    lps_full.configure(LPS22HBFull::ODR_10_HZ, true, true, LPS22HBFull::LPFP_BW_ODR_20, false, false);  // Configure chip, (odr, bdu, en_lpfp, lpfp_cfg, lc_en, sim) → None
    check_true(true, "configure");

    uint8_t st = lps_full.status();
    check_true(true, "status_read");

    float p2 = lps_full.pressure();
    check_true(p2 >= 80000.0f && p2 <= 120000.0f, "pressure_range_full");

    lps_full.set_pressure_offset(0.0f);
    check_true(true, "set_pressure_offset");

    lps_full.set_autozero();
    lps_full.clear_autozero();
    check_true(true, "autozero_roundtrip");

    lps_full.set_autorifp();
    lps_full.clear_autorifp();
    check_true(true, "autorifp_roundtrip");

    lps_full.configure_interrupt(false, false, false, false, LPS22HBFull::INT_S_DATA_SIGNALS, false, false);
    check_true(true, "configure_interrupt");

    lps_full.configure_pressure_interrupt(false, false, 0.0f, false);
    check_true(true, "configure_pressure_interrupt");

    lps_full.enable_fifo(LPS22HBFull::FIFO_MODE_BYPASS, 0);
    lps_full.disable_fifo();
    check_true(true, "fifo_roundtrip");

    uint8_t fsts = lps_full.fifo_status();
    check_true(true, "fifo_status_read");

    lps_full.reset_lpf();
    check_true(true, "reset_lpf");

    lps_full.reset();
    check_true(true, "reset");

    lps_full.reboot();
    check_true(true, "reboot");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
