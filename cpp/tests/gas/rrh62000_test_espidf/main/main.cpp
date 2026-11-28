// Auto-generated ESP-IDF test for RRH62000.
// Mirrors the Zephyr test for RRH62000; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include "Rrh62000.h"

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
    dev_cfg.device_address = 0x69;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    RRH62000Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Wait for start-up, check firmware version, () → bool
    uint8_t fw_major = 0, fw_minor = 0, ver_patch = 0;
    CHECK(sensor.firmware_version(fw_major, fw_minor), "firmware_version");
    CHECK(sensor.algorithm_version(fw_major, fw_minor, ver_patch), "algorithm_version");
    uint8_t uid[6];
    CHECK(sensor.unique_id(uid), "unique_id");
    RRH62000Measurement m;
    CHECK(sensor.read(m), "read");
    NEAR(m.pm2_5_ug_m3, 0.0f, 1000.0f, "read_pm");
    NEAR(m.temperature_c, -20.0f, 60.0f, "read_temperature");
    NEAR(m.humidity_rh, 0.0f, 100.0f, "read_humidity");
    NEAR(m.iaq, 0.0f, 5.0f, "read_iaq");
    RRH62000Data d;
    CHECK(sensor.read_all(d) && d.particles.nc_0_3 >= d.particles.nc_0_5, "read_all");
    RRH62000Status st;
    CHECK(sensor.read_status(st) && !st.fan, "read_status");
    RRH62000Particles p;
    CHECK(sensor.read_particles(p), "read_particles");
    CHECK(sensor.data_ready() >= 0, "data_ready");
    int avg = sensor.get_moving_average();
    CHECK(sensor.set_moving_average(20) && sensor.get_moving_average() == 20, "moving_average");
    if (avg > 0) sensor.set_moving_average((uint8_t)avg);
    int fan = sensor.get_fan_speed();
    CHECK(sensor.set_fan_speed(70) && sensor.get_fan_speed() == 70, "fan_speed");
    if (fan > 0) sensor.set_fan_speed((uint8_t)fan);
    int32_t interval = sensor.get_cleaning_interval();
    CHECK(sensor.set_cleaning_interval(86400) && sensor.get_cleaning_interval() == 86400, "cleaning_interval");
    if (interval >= 0) sensor.set_cleaning_interval((uint32_t)interval);
    int ctime = sensor.get_cleaning_time();
    CHECK(sensor.set_cleaning_time(12) && sensor.get_cleaning_time() == 12, "cleaning_time");
    if (ctime >= 0) sensor.set_cleaning_time((uint8_t)ctime);
    uint32_t ohms = 0;
    CHECK(sensor.read_mox_resistance(ohms) && ohms > 0, "mox_resistance");
    CHECK(sensor.cleaning_done() >= 0, "cleaning_done");
    CHECK(sensor.sleep(), "sleep");
    TEST_DELAY_MS(3000);
    CHECK(sensor.wake() && sensor.read(m), "wake_read");
    CHECK(sensor.soft_reset() && sensor.read(m), "soft_reset_read");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
