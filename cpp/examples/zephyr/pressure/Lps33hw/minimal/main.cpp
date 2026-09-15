#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Lps33hw.h"

#ifndef LPS33HW_I2C_NODE
#define LPS33HW_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LPS33HW_ADDR
#define LPS33HW_ADDR 0x5C
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LPS33HW_I2C_NODE);
    I2CConnectionZephyr connection(dev, LPS33HW_ADDR);
    LPS33HWMinimal lps(connection);                        // Create LPS33HW driver, (connection)

    for (int i = 0; i < 5; i++) {
        float p = lps.pressure();                         // Read pressure, () → float Pa
        float t = lps.temperature();                      // Read temperature, () → float °C
        printk("%.1f Pa, %.2f C\n", p, t);
        k_sleep(K_SECONDS(1));
    }

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}