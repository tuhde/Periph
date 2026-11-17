#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "HT16K33.h"

#ifndef HT16K33_I2C_NODE
#define HT16K33_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef HT16K33_ADDR
#define HT16K33_ADDR 0x70
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(HT16K33_I2C_NODE);
    I2CConnectionZephyr connection(dev, HT16K33_ADDR);
    HT16K33Minimal matrix(connection);                      // Create HT16K33 driver, (connection)

    for (uint8_t row = 0; row < HT16K33Minimal::ROWS; row++) {
        matrix.set_pixel(0, row);                         // Set one LED in the shadow RAM, (com 0–7, row 0–15, on) → bool
    }
    matrix.show();                                        // Write the shadow RAM to the chip, () → void
    k_msleep(1000);

    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
