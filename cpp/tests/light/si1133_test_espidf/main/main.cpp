// Auto-generated ESP-IDF test for SI1133.
// Mirrors the Zephyr test for SI1133; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include "Si1133.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((c), (l))
#define NEAR(v, lo, hi, l) check_true(((v) >= (lo) && (v) <= (hi)), (l))
#define TEST_DELAY_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))



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
    dev_cfg.device_address = 0x55;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Si1133Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Check PART_ID, reset, UV + white channels, () → bool
    uint8_t part = 0, hw = 0, rev = 0;
    CHECK(sensor.read_id(part, hw, rev) && part == 0x33, "part_id");
    CHECK((hw & 0x1F) == 0x03, "hw_id_impl");
    uint8_t state = 0, counter = 0;
    bool err = true;
    CHECK(sensor.read_response(state, err, counter) && !err, "response_no_error");
    int32_t uv = 0, white = 0;
    CHECK(sensor.read(uv, white), "read_ok");             // Forced measurement, (uv&, white&) → bool
    NEAR(uv, -8388608L, 8388607L, "read_uv_range");
    CHECK(white > 0, "read_white_positive_in_light");
    int32_t x = 0;
    CHECK(sensor.read_uv(x), "read_uv_scratch");
    CHECK(sensor.read_white(x) && x > 0, "read_white_scratch");
    CHECK(sensor.read_ir(x, Si1133Full::MUX_SMALL_IR), "read_ir_small");
    CHECK(sensor.read_ir(x, Si1133Full::MUX_LARGE_IR), "read_ir_large");
    CHECK(sensor.configure_channel(2, Si1133Full::MUX_LARGE_IR, 5, 0, 0, false, false, 0), "configure_channel");
    CHECK(sensor.enable_channels(0x07), "enable_channels");
    int32_t v[6] = {0, 0, 0, 0, 0, 0};
    size_t n = 0;
    CHECK(sensor.read_channels(v, n) && n == 3, "read_channels_count");
    NEAR(v[2], 0L, 65535L, "read_channels_16bit");
    uint8_t p = 0;
    CHECK(sensor.write_parameter(0x1C, 0x05) && sensor.read_parameter(0x1C, p) && p == 0x05, "parameter_roundtrip");
    CHECK(sensor.read_parameter(0x01, p) && p == 0x07, "chan_list_readback");
    CHECK(sensor.set_threshold(0, 1000) && sensor.set_channel_threshold(0, 1), "threshold");
    uint8_t hi = 0, lo = 0;
    CHECK(sensor.read_parameter(0x25, hi) && sensor.read_parameter(0x26, lo) && hi == 0x03 && lo == 0xE8, "threshold_readback");
    sensor.set_channel_threshold(0, 0);
    sensor.enable_channels(0x03);
    sensor.poll_interrupt();
    CHECK(sensor.start_autonomous(0.1f), "start_autonomous");
    TEST_DELAY_MS(500);
    CHECK((sensor.poll_interrupt() & 0x03) != 0, "autonomous_irq");
    CHECK(sensor.read_channels(v, n, false) && n == 2, "autonomous_values");
    CHECK(sensor.pause(), "pause");
    CHECK(sensor.reset(), "reset");
    CHECK(sensor.read_response(state, err, counter) && (state & Si1133Full::RESP_SLEEP), "reset_sleep");
    CHECK(sensor.read(uv, white) && white > 0, "read_after_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
