// Auto-generated ESP-IDF test for HTS221.
// Mirrors the Zephyr test for HTS221; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include "Hts221.h"

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
    dev_cfg.device_address = 0x5F;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    HTS221Full sensor(connection);


    CHECK(sensor.init(), "init");
    CHECK(sensor.who_am_i() == 0xBC, "who_am_i");
    HTS221Calibration cal = {};
    sensor.read_calibration(cal);
    CHECK(cal.h1_rh > cal.h0_rh && cal.t1_degc > cal.t0_degc, "calibration_plausible");
    float t = 0, rh = 0, hz = 0;
    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 85.0f, "read_temperature_range");
    NEAR(rh, 0.0f, 100.0f, "read_humidity_range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity_single");
    float tc = sensor.read_temperature();
    NEAR(tc, -40.0f, 85.0f, "read_temperature_single");
    CHECK(sensor.dew_point() <= tc + 1.0f, "dew_point_below_temperature");
    uint16_t ts = 0, hs = 0;
    CHECK(sensor.set_averaging(32, 64), "set_averaging");
    CHECK(sensor.get_averaging(ts, hs) && ts == 32 && hs == 64, "averaging_roundtrip");
    CHECK(sensor.set_averaging(16, 32), "set_averaging default");
    CHECK(sensor.set_output_data_rate(1), "set_output_data_rate");
    CHECK(sensor.get_output_data_rate(hz) && hz == 1.0f, "odr_roundtrip");
    TEST_DELAY_MS(1500);
    float ct = 0, crh = 0;
    CHECK(sensor.read_continuous(ct, crh), "read_continuous");
    NEAR(crh, 0.0f, 100.0f, "continuous_humidity_range");
    CHECK(sensor.set_output_data_rate(0), "set_output_data_rate one-shot");
    CHECK(sensor.set_heater(true) && sensor.is_heater_on(), "heater_on");
    CHECK(sensor.set_heater(false) && !sensor.is_heater_on(), "heater_off");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
