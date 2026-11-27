#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Rrh46410.h"

#ifndef RRH46410_I2C_NODE
#define RRH46410_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef RRH46410_ADDR
#define RRH46410_ADDR 0x38
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
    const struct device *i2c_dev = DEVICE_DT_GET(RRH46410_I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, RRH46410_ADDR);
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

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
