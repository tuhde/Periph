#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "SHT31.h"

#ifndef SHT31_I2C_NODE
#define SHT31_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef SHT31_ADDR
#define SHT31_ADDR 0x44
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
    const struct device *i2c_dev = DEVICE_DT_GET(SHT31_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, SHT31_ADDR);
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

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
