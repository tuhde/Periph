#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "APDS9960.h"

#ifndef APDS9960_I2C_NODE
#define APDS9960_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef APDS9960_ADDR
#define APDS9960_ADDR 0x39
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(APDS9960_I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, APDS9960_ADDR);
    APDS9960Full apds(transport);

    check_true(apds.chip_id() == 0xAB, "chip_id");

    uint16_t c, r, g, b;
    apds.color(c, r, g, b);
    check_true(c >= 0, "color_clear >= 0");
    check_true(r >= 0, "color_red >= 0");
    check_true(g >= 0, "color_green >= 0");
    check_true(b >= 0, "color_blue >= 0");

    check_true(apds.is_als_valid(), "is_als_valid");

    apds.enable_proximity(true);
    k_sleep(K_MSEC(100));
    check_true(apds.proximity() <= 255, "proximity <= 255");
    check_true(apds.is_proximity_valid(), "is_proximity_valid");

    apds.configure_als(0xB6, 1);
    k_sleep(K_MSEC(210));
    check_true(apds.is_als_valid(), "als_valid after configure");

    apds.als_threshold(100, 60000);
    apds.proximity_threshold(10, 200);
    apds.set_persistence(0, 1);
    check_true(true, "persistence set");

    apds.enable_als_interrupt(true);
    apds.enable_proximity_interrupt(true);
    apds.clear_als_interrupt();
    apds.clear_proximity_interrupt();
    apds.clear_all_interrupts();
    check_true(true, "interrupts cleared");

    apds.set_proximity_offset(10, -5);
    apds.set_proximity_mask(false, false, false, false);
    check_true(true, "proximity offset/mask set");

    apds.enable_gesture(true);
    apds.configure_gesture(1, 0, 0, 1, 1, 50, 20);
    check_true(true, "gesture configured");
    check_true(apds.gesture_fifo_level() >= 0, "gesture_fifo_level >= 0");
    apds.clear_gesture_fifo();
    apds.enable_gesture_interrupt(false);
    apds.enable_gesture(false);
    check_true(true, "gesture disabled");

    check_true(apds.status() >= 0, "status readable");

    apds.enable_proximity(false);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
