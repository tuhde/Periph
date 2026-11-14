// Auto-generated ESP-IDF test for SHT31.
// Mirrors the Zephyr test for SHT31; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include "SHT31.h"

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
    dev_cfg.device_address = 0x44;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    SHT31Full sensor(connection);

    float t = 0, rh = 0;
    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 125.0f, "temperature range");
    NEAR(rh, 0.0f, 100.0f, "humidity range");
    NEAR(sensor.read_temperature(), -40.0f, 125.0f, "read_temperature range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity range");
    uint16_t raw_t = 0, raw_rh = 0;
    CHECK(sensor.read_raw(raw_t, raw_rh), "read_raw ok");

    for (uint8_t level = 0; level < 3; level++) {
        sensor.set_repeatability(level);
        CHECK(sensor.read(t, rh), "repeatability read");
    }
    sensor.set_repeatability(SHT31Full::REPEATABILITY_HIGH);

    uint16_t status = 0;
    CHECK(sensor.read_status(status), "status readable");
    sensor.clear_status();
    CHECK(sensor.read_status(status) && !(status & 0x0010), "status bit 4 cleared");

    CHECK(sensor.start_periodic(2.0f, SHT31Full::REPEATABILITY_HIGH), "start_periodic");
    TEST_DELAY_MS(1200);
    CHECK(sensor.fetch(t, rh), "periodic fetch");
    sensor.stop_periodic();

    sensor.set_heater(true);
    CHECK(sensor.read_status(status) && (status & 0x2000), "heater bit set");
    sensor.set_heater(false);
    CHECK(sensor.read_status(status) && !(status & 0x2000), "heater bit cleared");

    NEAR(sensor.dew_point(), -60.0f, 60.0f, "dew point plausible");
    sensor.soft_reset();
    CHECK(sensor.read(t, rh), "read after soft reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
