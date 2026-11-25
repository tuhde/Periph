#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "SAA1064.h"

#ifndef SAA1064_I2C_NODE
#define SAA1064_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef SAA1064_ADDR
#define SAA1064_ADDR 0x38
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(SAA1064_I2C_NODE);
    I2CConnectionZephyr connection(dev, SAA1064_ADDR);
    SAA1064Minimal display(connection);                             // Create SAA1064 driver, (connection)
    uint16_t n = 0;
    for (int i = 0; i < 100; i++) {
        display.show_number(n);                                     // Show a right-aligned number, (value 0–9999) → bool
        n++;
        k_sleep(K_MSEC(100));
    }

    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
