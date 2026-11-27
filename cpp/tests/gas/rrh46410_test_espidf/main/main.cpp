// Auto-generated ESP-IDF test for RRH46410.
// Mirrors the Zephyr test for RRH46410; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include "Rrh46410.h"

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
    dev_cfg.device_address = 0x38;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    RRH46410Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Check product ID, start IAQ 2nd Gen mode, () → bool
    uint16_t pid = 0;
    uint8_t fw[3];
    CHECK(sensor.product_id(pid, fw) && pid == 0x2310, "product_id");
    uint8_t tn[6];
    CHECK(sensor.tracking_number(tn), "tracking_number");
    CHECK(sensor.get_mode() == RRH46410Full::MODE_IAQ_2ND_GEN, "mode_iaq");
    CHECK(sensor.set_humidity(50.0f) && sensor.status().raw == 0, "set_humidity");
    RRH46410Result r;
    CHECK(sensor.read(r), "read");
    CHECK(r.iaq >= 0.0f && r.iaq <= 25.5f, "read_iaq_range");
    CHECK(!r.warmed_up, "read_not_warmed_up");
    CHECK(sensor.wait_for_sample(5000), "wait_for_sample");
    CHECK(sensor.read_results(r), "read_results");
    CHECK(!sensor.poll_interrupt(), "poll_interrupt_false");
    CHECK(sensor.set_mode(RRH46410Full::MODE_PBAQ) && sensor.get_mode() == RRH46410Full::MODE_PBAQ, "mode_pbaq");
    CHECK(sensor.read(r) && r.pbaq, "read_pbaq");
    CHECK(sensor.config_gpio(0x03) && sensor.set_gpio(0x01) && (sensor.get_gpio() & 0x01), "set_gpio");
    CHECK(sensor.clear_gpio(0x01) && !(sensor.get_gpio() & 0x01), "clear_gpio");
    uint8_t mode = 0xFF;
    CHECK(sensor.send_command(0x10, nullptr, 0, &mode, 1) == 1 && mode == RRH46410Full::MODE_PBAQ, "send_command");
    CHECK(sensor.suspend() && sensor.get_mode() == RRH46410Full::MODE_SUSPEND, "suspend");
    CHECK(sensor.reset() && sensor.get_mode() == RRH46410Full::MODE_SUSPEND, "reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
