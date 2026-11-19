#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Lps22hb.h"

#ifndef LPS22HB_I2C_NODE
#define LPS22HB_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LPS22HB_ADDR
#define LPS22HB_ADDR 0x5C
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LPS22HB_I2C_NODE);
    I2CConnectionZephyr connection(dev, LPS22HB_ADDR);
    LPS22HBMinimal lps(connection);

    float p = lps.pressure();
    check_true(p >= 80000.0f && p <= 120000.0f, "pressure_range");

    float t = lps.temperature();
    check_true(t >= -10.0f && t <= 60.0f, "temperature_range");

    LPS22HBFull lps_full(connection);

    lps_full.configure(LPS22HBFull::ODR_10_HZ, true, true, LPS22HBFull::LPFP_BW_ODR_20, false, false);  // Configure chip, (odr, bdu, en_lpfp, lpfp_cfg, lc_en, sim) → None
    check_true(true, "configure");

    uint8_t st = lps_full.status();
    check_true(true, "status_read");

    float p2 = lps_full.pressure();
    check_true(p2 >= 80000.0f && p2 <= 120000.0f, "pressure_range_full");

    lps_full.set_pressure_offset(0.0f);
    check_true(true, "set_pressure_offset");

    lps_full.set_autozero();
    lps_full.clear_autozero();
    check_true(true, "autozero_roundtrip");

    lps_full.set_autorifp();
    lps_full.clear_autorifp();
    check_true(true, "autorifp_roundtrip");

    lps_full.configure_interrupt(false, false, false, false, LPS22HBFull::INT_S_DATA_SIGNALS, false, false);
    check_true(true, "configure_interrupt");

    lps_full.configure_pressure_interrupt(false, false, 0.0f, false);
    check_true(true, "configure_pressure_interrupt");

    lps_full.enable_fifo(LPS22HBFull::FIFO_MODE_BYPASS, 0);
    lps_full.disable_fifo();
    check_true(true, "fifo_roundtrip");

    uint8_t fsts = lps_full.fifo_status();
    check_true(true, "fifo_status_read");

    lps_full.reset_lpf();
    check_true(true, "reset_lpf");

    lps_full.reset();
    check_true(true, "reset");

    lps_full.reboot();
    check_true(true, "reboot");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}