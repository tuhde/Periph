// Auto-generated ESP-IDF test for Si7021.
// Mirrors the Zephyr test for Si7021; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include "Si7021.h"

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
    Si7021Full sensor(connection);

    float t = 0, rh = 0;
    sensor.software_reset();
    TEST_DELAY_MS(20);
    CHECK(sensor.device_type() == 0x15, "device type 0x15");
    CHECK(sensor.serial_number() != 0, "serial number");
    CHECK((sensor.read_user_register() & 0xBF) == 0x3A, "user register after reset");

    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 125.0f, "temperature range");
    NEAR(rh, 0.0f, 100.0f, "humidity range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity range");
    NEAR(sensor.read_temperature(), -40.0f, 125.0f, "read_temperature range");
    NEAR(sensor.read_temperature_previous(), -40.0f, 125.0f, "read_temperature_previous range");
    uint16_t raw = 0;
    CHECK(sensor.read_raw_humidity(raw), "read_raw_humidity ok");
    CHECK(sensor.read_raw_temperature(raw), "read_raw_temperature ok");

    static const uint8_t PAIRS[4][2] = {{12, 14}, {8, 12}, {10, 13}, {11, 11}};
    for (uint8_t i = 0; i < 4; i++) {
        uint8_t rb = 0, tb = 0;
        CHECK(sensor.set_resolution(PAIRS[i][0], PAIRS[i][1]), "set_resolution");
        sensor.get_resolution(rb, tb);
        CHECK(rb == PAIRS[i][0] && tb == PAIRS[i][1], "get_resolution");
        CHECK(sensor.read(t, rh), "read at resolution");
    }
    sensor.set_resolution(12, 14);
    NEAR(sensor.conversion_time(), 0.005f, 0.03f, "conversion time");

    sensor.set_heater(true);
    CHECK(sensor.is_heater_on(), "heater on");
    sensor.set_heater(false);
    CHECK(!sensor.is_heater_on(), "heater off");
    CHECK(!sensor.vdd_low(), "vdd ok");
    NEAR(sensor.dew_point(), -60.0f, 60.0f, "dew point plausible");
    sensor.software_reset();
    CHECK(sensor.read(t, rh), "read after software reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
