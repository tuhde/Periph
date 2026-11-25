// Auto-generated ESP-IDF test for BH1750.
// Mirrors the Zephyr test for BH1750; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include <math.h>
#include "Bh1750.h"

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
    dev_cfg.device_address = 0x23;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    BH1750Full sensor(connection);

    sensor.init();                                          // Power on, () → void
    float lux = 0;
    uint16_t raw = 0;
    CHECK(sensor.read(lux), "read ok");                     // Measure illuminance, (lux&) → bool
    NEAR(lux, 0.0f, 120000.0f, "read_range");
    CHECK(sensor.read_raw(raw), "read_raw");
    CHECK(sensor.measure_once(BH1750Full::MODE_H, lux), "measure_once_h");
    CHECK(sensor.measure_once(BH1750Full::MODE_H2, lux), "measure_once_h2");
    CHECK(sensor.measure_once(BH1750Full::MODE_L, lux), "measure_once_l");
    CHECK(fabsf(sensor.lux_per_count() - 1.0f / 1.2f) < 1e-5f, "lux_per_count_default");
    CHECK(fabsf(sensor.measurement_time() - 0.180f) < 1e-5f, "measurement_time_default");
    CHECK(sensor.start_continuous(BH1750Full::MODE_H), "start_continuous");
    TEST_DELAY_MS(200);
    CHECK(sensor.read_continuous(lux), "read_continuous");
    NEAR(lux, 0.0f, 120000.0f, "read_continuous_range");
    CHECK(sensor.set_measurement_time(138), "set_measurement_time");
    CHECK(sensor.get_measurement_time_register() == 138, "mtreg_roundtrip");
    CHECK(fabsf(sensor.measurement_time() - 0.360f) < 1e-5f, "measurement_time_scaled");
    CHECK(sensor.measure_once(BH1750Full::MODE_H, lux), "read_with_mtreg");
    CHECK(sensor.set_sensitivity(1.0f), "set_sensitivity");
    CHECK(sensor.get_measurement_time_register() == 69, "sensitivity_default");
    sensor.power_down();
    sensor.power_on();
    sensor.reset();
    CHECK(sensor.measure_once(BH1750Full::MODE_H, lux), "read_after_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
