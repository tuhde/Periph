#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "HS30xx.h"

#ifndef HS30xx_I2C_NODE
#define HS30xx_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef HS30xx_ADDR
#define HS30xx_ADDR 0x44
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
    const struct device *i2c_dev = DEVICE_DT_GET(HS30xx_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, HS30xx_ADDR);
    HS30xxFull sensor(connection);

    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0;
    uint8_t status = 9;

    CHECK(sensor.read(t, rh), "read ok");
    NEAR(t, -40.0f, 125.0f, "temperature range");
    NEAR(rh, 0.0f, 100.0f, "humidity range");
    NEAR(sensor.read_temperature(), -40.0f, 125.0f, "read_temperature range");
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read_humidity range");
    CHECK(sensor.read_raw(rh_raw, t_raw, status), "read_raw ok");
    CHECK(rh_raw <= 16383 && t_raw <= 16383, "raw ranges");
    CHECK(status == 0, "raw status valid");

    sensor.set_measurement_wait(60);
    NEAR(sensor.read_humidity(), 0.0f, 100.0f, "read with 60 ms wait");
    sensor.set_measurement_wait(40);

    NEAR(sensor.dew_point(), -60.0f, 60.0f, "dew point plausible");

    // NVM access (resolution, ID) needs a power-cycle helper and is not exercised here.

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
