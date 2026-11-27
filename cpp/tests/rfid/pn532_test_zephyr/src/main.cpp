#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Pn532.h"

#ifndef PN532_I2C_NODE
#define PN532_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef PN532_ADDR
#define PN532_ADDR 0x24
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((c), (l))
#define NEAR(v, lo, hi, l) check_true(((v) >= (lo) && (v) <= (hi)), (l))
#define TEST_DELAY_MS(ms) k_sleep(K_MSEC(ms))



int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(PN532_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, PN532_ADDR);
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

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
