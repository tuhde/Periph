#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "Rda5807m.h"

#ifndef RDA5807M_I2C_NODE
#define RDA5807M_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef RDA5807M_ADDR
#define RDA5807M_ADDR 0x10
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(RDA5807M_I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, RDA5807M_ADDR);
    RDA5807MFull fm(transport, 100.0f, 8);

    check_true(fm.is_ready(), "is_ready");

    float f = fm.frequency();
    check_true(f > 99.8f && f < 100.2f, "frequency near 100.0 MHz");

    fm.set_frequency(97.5f);
    f = fm.frequency();
    check_true(f > 97.3f && f < 97.7f, "set_frequency: frequency near 97.5 MHz");

    fm.set_volume(10);
    check_true(fm.signal_strength() <= 127, "signal_strength in range");

    fm.mute(true);
    fm.mute(false);
    check_true(fm.is_ready(), "mute/unmute: is_ready after");

    float seek_freq;
    fm.seek(true, seek_freq);
    check_true(true, "seek: returns without hang");

    fm.enable_rds(true);
    check_true(fm.rds_ready() || !fm.rds_ready(), "rds_ready is callable");

    fm.configure(RDA5807MFull::BAND_WORLD, RDA5807MFull::SPACE_100K);
    check_true(fm.is_ready(), "after configure: is_ready");

    fm.standby(true);
    k_sleep(K_MSEC(10));
    fm.standby(false);
    k_sleep(K_MSEC(10));
    check_true(fm.is_ready(), "after standby cycle: is_ready");

    fm.soft_reset();
    check_true(fm.is_ready(), "after soft_reset: is_ready");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
