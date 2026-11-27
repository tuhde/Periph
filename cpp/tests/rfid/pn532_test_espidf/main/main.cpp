// Auto-generated ESP-IDF test for PN532.
// Mirrors the Zephyr test for PN532; prints PASS/FAIL and exits.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "esp_idf_version.h"
#include "I2CConnectionESPIDF.h"
#include "Pn532.h"

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
    dev_cfg.device_address = 0x24;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    PN532Full reader(connection);

    CHECK(reader.init(), "init");                          // Wake, check firmware, SAM normal mode, finite retries, () → bool
    PN532FirmwareVersion fw;
    CHECK(reader.firmware_version(fw) && fw.ic == 0x32, "firmware_ic");
    CHECK(fw.iso14443a, "firmware_support_a");
    PN532GeneralStatus gs;
    CHECK(reader.general_status(gs) && gs.targets <= 2, "general_status_targets");
    CHECK(reader.self_test(), "self_test_echo");
    CHECK(reader.self_test(0x01), "self_test_rom");
    uint8_t resp[8];
    CHECK(reader.send_command(0x02, nullptr, 0, resp, sizeof(resp)) == 4 && resp[0] == 0x32, "send_command");
    CHECK(reader.start_command(0x02), "start_command");
    CHECK(reader.wait_ready(1000), "wait_ready");
    CHECK(reader.poll_interrupt(), "poll_interrupt");
    CHECK(reader.read_response(0x02, resp, sizeof(resp)) == 4 && resp[0] == 0x32, "read_response");
    uint8_t reg = 0, reg2 = 0;
    CHECK(reader.read_register(0x6302, reg), "read_register");
    CHECK(reader.write_register(0x6302, reg), "write_register");
    CHECK(reader.read_register(0x6302, reg2) && reg2 == reg, "register_roundtrip");
    CHECK(reader.set_rf_field(false) && reader.set_rf_field(true), "rf_field_toggle");
    CHECK(reader.set_max_retries(2), "set_max_retries");
    uint8_t uid[10];
    uint8_t len = reader.read_uid(uid);                    // Detect a type A card, (uid*, timeoutMs=1000) → uint8_t length
    CHECK(reader.last_error() == 0 && (len == 0 || len == 4 || len == 7 || len == 10), "read_uid_returns");
    PN532Target t[1];
    int n = reader.list_passive_targets(t);
    CHECK(n >= 0, "list_passive_targets");
    if (n > 0) reader.release(t[0].tg);
    CHECK(reader.power_down(), "power_down");
    TEST_DELAY_MS(10);
    CHECK(reader.firmware_version(fw) && fw.ic == 0x32, "wake_after_power_down");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
