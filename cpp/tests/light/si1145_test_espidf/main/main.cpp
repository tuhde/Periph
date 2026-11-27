// Auto-generated ESP-IDF test for SI1145.
// Mirrors the Zephyr test for SI1145; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include "Si1145.h"

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
    dev_cfg.device_address = 0x60;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    Si1145Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Check PART_ID, HW_KEY, UV + ALS + PS1, () → bool
    uint8_t part = 0, rev = 0, seq = 0;
    CHECK(sensor.read_id(part, rev, seq) && part >= 0x45 && part <= 0x47, "part_id");
    uint8_t counter = 0, err = 0xFF;
    CHECK(sensor.read_response(counter, err) && err == 0, "response_no_error");
    Si1145Reading r;
    CHECK(sensor.read(r), "read_ok");                      // Forced PS + ALS, (reading&) → bool
    CHECK(r.uv_index >= 0.0f && r.uv_index < 20.0f, "read_uv_index_range");
    CHECK(r.visible > 0, "read_visible_positive");
    CHECK(r.infrared > 0, "read_infrared_positive");
    uint16_t v = 0;
    float uv = 0.0f;
    CHECK(sensor.read_proximity(v), "read_proximity_single");
    CHECK(sensor.read_uv_index(uv) && uv < 20.0f, "read_uv_index_single");
    CHECK(sensor.read_visible(v) && v > 0, "read_visible_single");
    CHECK(sensor.read_infrared(v) && v > 0, "read_infrared_single");
    Si1145Raw raw;
    CHECK(sensor.read_raw(raw), "read_raw");
    CHECK(sensor.read_temperature_raw(v) && v > 0, "read_temperature_raw");
    CHECK(sensor.read_vdd_raw(v) && v > 0, "read_vdd_raw");
    uint16_t dark = 0, lit = 0;
    CHECK(sensor.set_led_current(1, 0) && sensor.read_proximity(dark), "led_off_read");
    CHECK(sensor.set_led_current(1, 15) && sensor.read_proximity(lit), "led_max_read");
    CHECK(lit >= dark, "led_current_effect");
    CHECK(sensor.set_led_current(1, 3), "led_default");
    CHECK(!sensor.set_led_current(1, 16), "led_level_invalid");
    CHECK(sensor.set_ps_photodiode(1, true), "set_ps_photodiode");
    CHECK(sensor.set_gain(2, 2, 0) && sensor.read_visible(v) && v > 0, "gain_read");
    CHECK(sensor.set_gain(), "gain_default");
    CHECK(sensor.set_high_range(true, true, false) && sensor.read_visible(v), "high_range_read");
    CHECK(sensor.set_high_range(false, false, false), "high_range_off");
    CHECK(sensor.set_alignment(), "set_alignment");
    const uint8_t ucoef[4] = {0x7B, 0x6B, 0x01, 0x00};
    CHECK(sensor.set_uv_coefficients(ucoef), "set_uv_coefficients");
    uint8_t cal[12];
    CHECK(sensor.read_calibration(cal), "read_calibration");
    CHECK(sensor.set_channels(true, true, true, true), "set_channels");
    sensor.poll_interrupt();
    CHECK(sensor.start_autonomous(0.05f), "start_autonomous");
    TEST_DELAY_MS(500);
    CHECK((sensor.poll_interrupt() & 0x05) != 0, "autonomous_irq");
    CHECK(sensor.pause(), "pause");
    CHECK(sensor.force(), "force");
    TEST_DELAY_MS(100);
    CHECK(sensor.poll_interrupt() != 0, "force_irq");
    CHECK(sensor.enable_interrupt(Si1145Full::SOURCE_CMD), "enable_interrupt");
    CHECK(sensor.disable_interrupt(Si1145Full::SOURCE_CMD), "disable_interrupt");
    CHECK(sensor.reset(), "reset");
    CHECK(sensor.read_response(counter, err) && err == 0, "reset_no_error");
    CHECK(sensor.read(r) && r.visible > 0, "read_after_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
