#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include <math.h>
#include "Htu31d.h"

#ifndef HTU31D_I2C_NODE
#define HTU31D_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef HTU31D_ADDR
#define HTU31D_ADDR 0x40
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
    const struct device *i2c_dev = DEVICE_DT_GET(HTU31D_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HTU31D_ADDR);
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

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
