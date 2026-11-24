// Auto-generated ESP-IDF test for HTU31D.
// Mirrors the Zephyr test for HTU31D; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include <math.h>
#include "Htu31d.h"

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
    dev_cfg.device_address = 0x40;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    HTU31DFull sensor(connection);

    sensor.init();                                          // Soft reset, () → void
    float t = 0, rh = 0;
    CHECK(sensor.read(t, rh), "read ok");                   // Read temperature and humidity, (temperature_c&, humidity_pct&) → bool
    NEAR(t, -40.0f, 125.0f, "read_temperature_range");
    NEAR(rh, 0.0f, 100.0f, "read_humidity_range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity_single");
    float tc = sensor.read_temperature();
    NEAR(tc, -40.0f, 125.0f, "read_temperature_single");
    uint16_t tr = 0, rr = 0;
    CHECK(sensor.read_raw(tr, rr), "read_raw");
    CHECK(sensor.dew_point() <= tc + 1.0f, "dew_point_below_temperature");
    uint32_t serial = 0;
    CHECK(sensor.serial_number(serial) && serial <= 0xFFFFFF, "serial_number");
    CHECK((sensor.diagnostic() & HTU31DFull::DIAG_NVM_ERR) == 0, "diagnostic_no_nvm_error");
    uint8_t orh = 0, ot = 0;
    for (uint8_t osr = 0; osr < 4; osr++) {
        CHECK(sensor.set_resolution(osr, osr), "set_resolution");
        sensor.get_resolution(orh, ot);
        CHECK(orh == osr && ot == osr, "resolution_roundtrip");
        CHECK(sensor.read(t, rh) && rh >= 0.0f && rh <= 100.0f, "read_at_osr");
    }
    sensor.set_resolution(3, 3);
    CHECK(fabsf(sensor.conversion_time() - 0.02032f) < 1e-4f, "conversion_time_osr33");
    sensor.start_conversion();
    TEST_DELAY_MS(30);
    CHECK(sensor.read_result(t, rh) && rh >= 0.0f && rh <= 100.0f, "start_conversion_read_result");
    sensor.set_heater(true);
    CHECK(sensor.is_heater_on(), "heater_on");
    sensor.set_heater(false);
    CHECK(!sensor.is_heater_on(), "heater_off");
    sensor.software_reset();
    CHECK(sensor.read(t, rh) && rh >= 0.0f && rh <= 100.0f, "read_after_reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
