// Auto-generated ESP-IDF test for AM2315.
// Mirrors the Zephyr test for AM2315; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include "AM2315.h"

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
    dev_cfg.device_address = 0x5C;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AM2315Full sensor(connection);

    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0, model = 0, user1 = 0, user2 = 0;
    uint8_t version = 0, regs[7] = {0};
    uint32_t device_id = 0;

    CHECK(sensor.read_device_info(model, version, device_id), "read_device_info ok");
    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 125.0f, "temperature range");
    NEAR(rh, 0.0f, 100.0f, "humidity range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity range");
    NEAR(sensor.read_temperature(), -40.0f, 125.0f, "read_temperature range");
    CHECK(sensor.read_raw(rh_raw, t_raw), "read_raw ok");
    NEAR((float)rh_raw, 0.0f, 1000.0f, "raw humidity range");
    NEAR((float)(t_raw & 0x7FFF), 0.0f, 1250.0f, "raw temperature range");
    CHECK(sensor.read_fresh(t, rh), "read_fresh ok");
    NEAR(t, -40.0f, 125.0f, "fresh temperature range");
    CHECK(sensor.read_registers(0x00, 4, regs), "read_registers ok");

    CHECK(sensor.write_user_registers(0x1234, 0xABCD), "write_user_registers ok");
    CHECK(sensor.read_user_registers(user1, user2), "read_user_registers ok");
    CHECK(user1 == 0x1234 && user2 == 0xABCD, "user registers round trip");
    sensor.write_user_registers(0, 0);
    sensor.wake();

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
