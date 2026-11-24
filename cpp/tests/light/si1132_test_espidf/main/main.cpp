// ESP-IDF test for Si1132.
// Mirrors the Zephyr test for Si1132; prints PASS/FAIL and exits.

#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "Si1132.h"

static int passed = 0, failed = 0;

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
    dev_cfg.device_address = Si1132Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Si1132Minimal sensor(connection);                    // Create Si1132 driver, (connection)
    Si1132Full full(connection);                          // Create Si1132 Full driver, (connection)

    check_true(full.init(), "init");                        // Initialize the chip, () → bool
    float uv = 0; uint16_t vis = 0, ir = 0, aux = 0;
    check_true(full.read(uv, vis, ir), "read");             // Read UV index and ALS counts, (uv&, visible&, infrared&) → bool
    check_true(uv >= 0.0f && uv < 20.0f, "read_uv_range");
    check_true(full.read_uv_index() < 20.0f, "read_uv_index");
    check_true(full.read_visible(vis) && full.read_infrared(ir), "read_single");
    check_true(full.read_raw(vis, ir, aux), "read_raw");
    check_true(full.read_temperature_raw(aux), "temperature_raw");
    check_true(full.read_vdd_raw(aux), "vdd_raw");
    check_true(full.set_gain(2, 2) && full.read_visible(vis), "gain_read");
    check_true(full.set_gain(0, 0), "gain_default");
    check_true(full.set_high_range(true, true) && full.read_visible(vis), "high_range_read");
    check_true(full.set_high_range(false, false), "high_range_off");
    check_true(full.set_channels(true, false, false) && full.read(uv, vis, ir), "uv_only_read");
    check_true(full.set_channels(true, true, true), "channels_all");
    check_true(!full.set_channels(false, false, false), "channels_invalid");
    uint8_t cal[12];
    check_true(full.read_calibration(cal), "read_calibration");
    check_true(full.force(), "force");
    vTaskDelay(pdMS_TO_TICKS(50));
    check_true(full.measurement_ready(), "measurement_ready");
    uint8_t counter = 0, error = 0;
    check_true(full.read_response(counter, error) && error == 0, "response_no_error");
    full.poll_interrupt();
    check_true(full.start_autonomous(0.1f), "autonomous");
    vTaskDelay(pdMS_TO_TICKS(500));
    check_true(full.measurement_ready(), "autonomous_ready");
    check_true(full.pause() && full.start_autonomous(0.0f), "pause");
    check_true(full.enable_interrupt() && full.disable_interrupt(), "interrupt_pin");
    check_true(full.reset(), "reset");
    check_true(full.read(uv, vis, ir), "read_after_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
