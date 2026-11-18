#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Is31fl3731.h"

#ifndef IS31FL3731_I2C_NODE
#define IS31FL3731_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef IS31FL3731_ADDR
#define IS31FL3731_ADDR 0x74
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(IS31FL3731_I2C_NODE);
    I2CConnectionZephyr connection(dev, IS31FL3731_ADDR);
    Is31fl3731Minimal matrix(connection);                 // Create IS31FL3731 driver, (connection)

    for (uint8_t col = 0; col < Is31fl3731Minimal::COLS; col++) {
        matrix.set_pixel(0, col, 128);                    // Set one LED's PWM brightness, (row 0–8, col 0–15, brightness 0–255) → bool
    }
    k_msleep(1000);
    matrix.fill(32);                                      // Set every LED's PWM brightness, (brightness 0–255) → void
    k_msleep(1000);
    matrix.clear();                                       // Switch all LEDs off, () → void

    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
