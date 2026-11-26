// Auto-generated ESP-IDF test for CCS811.
// Mirrors the Zephyr test for CCS811; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include "Ccs811.h"

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
    dev_cfg.device_address = 0x5A;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    CCS811Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Check HW_ID, start application, mode 1, () → bool
    uint8_t hw_id = 0, hw_version = 0, st = 0, err = 0;
    CHECK(sensor.hardware_version(hw_id, hw_version) && hw_id == 0x81, "hardware_id");
    CHECK((hw_version & 0xF0) == 0x10, "hardware_version");
    CCS811Version boot = {0, 0, 0}, app = {0, 0, 0};
    CHECK(sensor.firmware_version(boot, app) && app.major >= 1, "firmware_version");
    CHECK(sensor.status(st) && (st & CCS811Full::STATUS_FW_MODE), "status_app_mode");
    CHECK(!(st & CCS811Full::STATUS_ERROR), "status_no_error");
    uint16_t eco2 = 0, tvoc = 0;
    CHECK(sensor.read(eco2, tvoc), "read_ok");             // Next sample, (eco2_ppm&, tvoc_ppb&) → bool
    NEAR(eco2, 400, 8192, "read_eco2_range");
    NEAR(tvoc, 0, 1187, "read_tvoc_range");
    CHECK(sensor.read_eco2(eco2) && eco2 >= 400 && eco2 <= 8192, "read_eco2_single");
    CHECK(sensor.read_tvoc(tvoc) && tvoc <= 1187, "read_tvoc_single");
    uint8_t current = 0;
    float voltage = 0;
    CHECK(sensor.read_raw(current, voltage) && current <= 63, "read_raw_current");
    NEAR(voltage, 0.0f, 1.65f, "read_raw_voltage");
    bool ready = true;
    CHECK(sensor.data_ready(ready) && !ready, "data_ready_cleared");
    CHECK(sensor.set_environment(50.0f, 25.0f), "set_environment");
    CHECK(sensor.set_thresholds(1000, 2000, 50), "set_thresholds");
    uint16_t v_ref = 0, v_ntc = 0;
    CHECK(sensor.read_ntc(v_ref, v_ntc), "read_ntc");
    uint16_t baseline = 0;
    CHECK(sensor.get_baseline(baseline) && sensor.set_baseline(baseline), "baseline_roundtrip");
    CHECK(sensor.error(err) && err == 0, "no_error");
    sensor.enable_interrupt(CCS811Full::SOURCE_DATA_READY);
    TEST_DELAY_MS(1200);
    CHECK((sensor.poll_interrupt() & CCS811Full::STATUS_DATA_READY) != 0, "poll_interrupt_data_ready");
    sensor.read(eco2, tvoc);
    sensor.disable_interrupt(CCS811Full::SOURCE_DATA_READY);
    CHECK(sensor.set_mode(4), "set_mode_4");
    TEST_DELAY_MS(600);
    CHECK(sensor.read_raw(current, voltage) && current <= 63, "mode_4_raw");
    sensor.set_mode(0);
    sensor.set_mode(1);
    CHECK(sensor.soft_reset(), "soft_reset");
    CHECK(sensor.status(st) && (st & CCS811Full::STATUS_FW_MODE), "status_after_reset");
    CHECK(sensor.read(eco2, tvoc) && eco2 >= 400 && eco2 <= 8192, "read_after_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
