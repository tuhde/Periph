#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "PCF8578.h"

#ifndef PCF8578_I2C_NODE
#define PCF8578_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef PCF8578_ADDR
#define PCF8578_ADDR 0x3C
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(PCF8578_I2C_NODE);
    I2CConnectionZephyr connection(dev, PCF8578_ADDR);
    PCF8578Minimal lcd(connection, 16);                       // Create PCF8578 driver, (connection, rows=16 8/16/24/32)

    lcd.clear();                                              // Clear display RAM, () → void
    lcd.set_pixel(0, 0);                                      // Set one pixel, (x 0–23, y 0–15, on=true) → void
    lcd.set_pixel(23, 15);                                    // Set opposite corner, (x 0–23, y 0–15, on=true) → void
    static const uint8_t frame[] = {0xFF, 0x81, 0x81, 0xFF};
    lcd.write_columns(4, 0, frame, sizeof(frame));            // Write column bytes, (x 0–23, bank 0–1, data, len) → bool

    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
