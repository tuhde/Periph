// Auto-generated ESP-IDF test for AS7262.
// Mirrors the Zephyr test for AS7262; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include "As7262.h"

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
    dev_cfg.device_address = 0x49;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AS7262Full sensor(connection);

    AS7262Channels ch = {};
    AS7262RawChannels raw = {};
    uint8_t u8a = 0, u8b = 0;
    uint16_t u16 = 0;

    CHECK(sensor.begin(), "begin");
    CHECK(sensor.device_type() == 0x40, "device type");
    CHECK(sensor.hardware_version(u8a, u8b) && u8a == 0x40 && u8b == 0x3E, "hardware version");
    NEAR(sensor.temperature(), -40.0f, 125.0f, "temperature range");

    CHECK(sensor.read(ch), "read ok");
    CHECK(ch.violet >= 0.0f && ch.blue >= 0.0f && ch.green >= 0.0f && ch.yellow >= 0.0f && ch.orange >= 0.0f && ch.red >= 0.0f, "calibrated channels");
    CHECK(sensor.read_raw(raw), "read_raw ok");
    CHECK(sensor.read_channel_raw(2, u16), "read_channel_raw");

    CHECK(sensor.set_gain(64), "set_gain 64");
    CHECK(sensor.get_gain() == 64.0f, "gain readback");
    CHECK(sensor.set_gain(16), "set_gain 16");
    CHECK(sensor.set_integration_time(50.0f), "set_integration_time");
    NEAR(sensor.get_integration_time(), 50.3f, 50.5f, "integration time readback");
    CHECK(sensor.set_mode(2), "set_mode 2");
    CHECK(sensor.get_mode() == 2, "mode readback");
    CHECK(sensor.set_mode(3), "set_mode 3");

    CHECK(sensor.measure(), "measure");
    TEST_DELAY_MS(600);
    CHECK(sensor.data_ready(), "data ready");
    CHECK(sensor.read_calibrated(ch), "read_calibrated");

    CHECK(sensor.set_indicator_led(true, 4.0f), "indicator led on");
    CHECK(sensor.set_indicator_led(false), "indicator led off");
    CHECK(sensor.soft_reset(), "soft_reset");
    NEAR(sensor.get_integration_time(), 700.0f, 714.0f, "reset int_t");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
