#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Ccs811.h"

#ifndef CCS811_I2C_NODE
#define CCS811_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef CCS811_ADDR
#define CCS811_ADDR 0x5A
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
    const struct device *i2c_dev = DEVICE_DT_GET(CCS811_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, CCS811_ADDR);
    CCS811Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Check HW_ID, start application, mode 1, () → bool
    uint8_t hw_id = 0, hw_version = 0, st = 0, err = 0;
    CHECK(sensor.hardware_version(hw_id, hw_version) && hw_id == 0x81, "hardware_id");
    CHECK((hw_version & 0xF0) == 0x10, "hardware_version");
    CCS811Version boot = {0, 0, 0}, app = {0, 0, 0};
    CHECK(sensor.firmware_version(boot, app) && app.major >= 1, "firmware_version");
    CHECK(sensor.status(st) && (st & CCS811Full::STATUS_FW_MODE), "status_app_mode");
    CHECK(!(st & CCS811Full::STATUS_ERROR), "status_no_error");
    uint16_t eco2 = 0, tvoc = 0;
    CHECK(sensor.read(eco2, tvoc), "read_ok");             // Next sample, (eco2_ppm&, tvoc_ppb&) → bool
    NEAR(eco2, 400, 8192, "read_eco2_range");
    NEAR(tvoc, 0, 1187, "read_tvoc_range");
    CHECK(sensor.read_eco2(eco2) && eco2 >= 400 && eco2 <= 8192, "read_eco2_single");
    CHECK(sensor.read_tvoc(tvoc) && tvoc <= 1187, "read_tvoc_single");
    uint8_t current = 0;
    float voltage = 0;
    CHECK(sensor.read_raw(current, voltage) && current <= 63, "read_raw_current");
    NEAR(voltage, 0.0f, 1.65f, "read_raw_voltage");
    bool ready = true;
    CHECK(sensor.data_ready(ready) && !ready, "data_ready_cleared");
    CHECK(sensor.set_environment(50.0f, 25.0f), "set_environment");
    CHECK(sensor.set_thresholds(1000, 2000, 50), "set_thresholds");
    uint16_t v_ref = 0, v_ntc = 0;
    CHECK(sensor.read_ntc(v_ref, v_ntc), "read_ntc");
    uint16_t baseline = 0;
    CHECK(sensor.get_baseline(baseline) && sensor.set_baseline(baseline), "baseline_roundtrip");
    CHECK(sensor.error(err) && err == 0, "no_error");
    sensor.enable_interrupt(CCS811Full::SOURCE_DATA_READY);
    TEST_DELAY_MS(1200);
    CHECK((sensor.poll_interrupt() & CCS811Full::STATUS_DATA_READY) != 0, "poll_interrupt_data_ready");
    sensor.read(eco2, tvoc);
    sensor.disable_interrupt(CCS811Full::SOURCE_DATA_READY);
    CHECK(sensor.set_mode(4), "set_mode_4");
    TEST_DELAY_MS(600);
    CHECK(sensor.read_raw(current, voltage) && current <= 63, "mode_4_raw");
    sensor.set_mode(0);
    sensor.set_mode(1);
    CHECK(sensor.soft_reset(), "soft_reset");
    CHECK(sensor.status(st) && (st & CCS811Full::STATUS_FW_MODE), "status_after_reset");
    CHECK(sensor.read(eco2, tvoc) && eco2 >= 400 && eco2 <= 8192, "read_after_reset");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
