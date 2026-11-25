// Auto-generated ESP-IDF test for SHTC3.
// Mirrors the Zephyr test for SHTC3; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include <math.h>
#include "Shtc3.h"

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
    dev_cfg.device_address = 0x70;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    SHTC3Full sensor(connection);

    CHECK(sensor.init(), "init ok");                        // Soft reset and ID check, () → bool
    float t = 0, rh = 0;
    uint16_t tr = 0, rr = 0, id = 0;
    CHECK(sensor.read(t, rh), "read ok");                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
    NEAR(t, -45.0f, 130.0f, "read_temperature_range");
    NEAR(rh, 0.0f, 100.0f, "read_humidity_range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity_single");
    float tc = sensor.read_temperature();
    NEAR(tc, -45.0f, 130.0f, "read_temperature_single");
    CHECK(sensor.read_raw(tr, rr), "read_raw");
    CHECK(sensor.dew_point() <= tc + 1.0f, "dew_point_below_temperature");
    CHECK(sensor.read_id(id) && (id & 0x083F) == 0x0807, "read_id_product_code");
    sensor.set_low_power(true);
    CHECK(sensor.read(t, rh), "low_power_read");
    sensor.set_low_power(false);
    sensor.set_sleep_between(false);
    CHECK(sensor.read(t, rh), "read_without_sleep");
    CHECK(sensor.read(t, rh), "read_without_sleep_again");
    sensor.sleep();
    sensor.wake();
    sensor.set_sleep_between(true);
    sensor.set_clock_stretching(true);
    CHECK(sensor.read(t, rh), "read_with_clock_stretching");
    sensor.set_clock_stretching(false);
    sensor.soft_reset();
    CHECK(sensor.read(t, rh), "read_after_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
