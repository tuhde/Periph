#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Si7021.h"

#ifndef Si7021_I2C_NODE
#define Si7021_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef Si7021_ADDR
#define Si7021_ADDR 0x40
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
    const struct device *i2c_dev = DEVICE_DT_GET(Si7021_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, Si7021_ADDR);
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

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
