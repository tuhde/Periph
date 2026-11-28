#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Rrh62000.h"

#ifndef RRH62000_I2C_NODE
#define RRH62000_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef RRH62000_ADDR
#define RRH62000_ADDR 0x69
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
    const struct device *i2c_dev = DEVICE_DT_GET(RRH62000_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, RRH62000_ADDR);
    RRH62000Full sensor(connection);

    CHECK(sensor.init(), "init");                          // Wait for start-up, check firmware version, () → bool
    uint8_t fw_major = 0, fw_minor = 0, ver_patch = 0;
    CHECK(sensor.firmware_version(fw_major, fw_minor), "firmware_version");
    CHECK(sensor.algorithm_version(fw_major, fw_minor, ver_patch), "algorithm_version");
    uint8_t uid[6];
    CHECK(sensor.unique_id(uid), "unique_id");
    RRH62000Measurement m;
    CHECK(sensor.read(m), "read");
    NEAR(m.pm2_5_ug_m3, 0.0f, 1000.0f, "read_pm");
    NEAR(m.temperature_c, -20.0f, 60.0f, "read_temperature");
    NEAR(m.humidity_rh, 0.0f, 100.0f, "read_humidity");
    NEAR(m.iaq, 0.0f, 5.0f, "read_iaq");
    RRH62000Data d;
    CHECK(sensor.read_all(d) && d.particles.nc_0_3 >= d.particles.nc_0_5, "read_all");
    RRH62000Status st;
    CHECK(sensor.read_status(st) && !st.fan, "read_status");
    RRH62000Particles p;
    CHECK(sensor.read_particles(p), "read_particles");
    CHECK(sensor.data_ready() >= 0, "data_ready");
    int avg = sensor.get_moving_average();
    CHECK(sensor.set_moving_average(20) && sensor.get_moving_average() == 20, "moving_average");
    if (avg > 0) sensor.set_moving_average((uint8_t)avg);
    int fan = sensor.get_fan_speed();
    CHECK(sensor.set_fan_speed(70) && sensor.get_fan_speed() == 70, "fan_speed");
    if (fan > 0) sensor.set_fan_speed((uint8_t)fan);
    int32_t interval = sensor.get_cleaning_interval();
    CHECK(sensor.set_cleaning_interval(86400) && sensor.get_cleaning_interval() == 86400, "cleaning_interval");
    if (interval >= 0) sensor.set_cleaning_interval((uint32_t)interval);
    int ctime = sensor.get_cleaning_time();
    CHECK(sensor.set_cleaning_time(12) && sensor.get_cleaning_time() == 12, "cleaning_time");
    if (ctime >= 0) sensor.set_cleaning_time((uint8_t)ctime);
    uint32_t ohms = 0;
    CHECK(sensor.read_mox_resistance(ohms) && ohms > 0, "mox_resistance");
    CHECK(sensor.cleaning_done() >= 0, "cleaning_done");
    CHECK(sensor.sleep(), "sleep");
    TEST_DELAY_MS(3000);
    CHECK(sensor.wake() && sensor.read(m), "wake_read");
    CHECK(sensor.soft_reset() && sensor.read(m), "soft_reset_read");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
