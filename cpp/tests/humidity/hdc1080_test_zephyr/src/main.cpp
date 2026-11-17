#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "HDC1080.h"

#ifndef HDC1080_I2C_NODE
#define HDC1080_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef HDC1080_ADDR
#define HDC1080_ADDR 0x40
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
    const struct device *i2c_dev = DEVICE_DT_GET(HDC1080_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HDC1080_ADDR);
    HDC1080Full sensor(connection);

    float t = 0, rh = 0;
    sensor.init();
    CHECK((sensor.read_config() & 0xFF00) == 0x1000, "config after init");

    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 125.0f, "temperature range");
    NEAR(rh, 0.0f, 100.0f, "humidity range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity range");
    NEAR(sensor.read_temperature(), -40.0f, 125.0f, "read_temperature range");
    uint16_t raw_t = 0, raw_rh = 0;
    CHECK(sensor.read_raw(raw_t, raw_rh), "read_raw ok");

    static const uint8_t TB[4] = {14, 11, 11, 14};
    static const uint8_t HB[4] = {14, 11, 8, 8};
    for (uint8_t i = 0; i < 4; i++) {
        CHECK(sensor.set_resolution(TB[i], HB[i]), "set_resolution");
        CHECK(sensor.read(t, rh), "read at resolution");
    }
    sensor.set_resolution(14, 14);

    sensor.set_heater(true);
    CHECK(sensor.is_heater_on(), "heater on");
    sensor.set_heater(false);
    CHECK(!sensor.is_heater_on(), "heater off");
    CHECK(!sensor.battery_low(), "battery ok");
    CHECK(sensor.serial_id() != 0, "serial id nonzero");
    CHECK(sensor.manufacturer_id() == 0x5449, "manufacturer id");
    CHECK(sensor.device_id() == 0x1050, "device id");
    NEAR(sensor.conversion_time(), 0.005f, 0.02f, "conversion time");
    sensor.software_reset();
    CHECK(sensor.read(t, rh), "read after software reset");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
