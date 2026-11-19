#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Pca9685.h"

#ifndef PCA9685_I2C_NODE
#define PCA9685_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef PCA9685_ADDR
#define PCA9685_ADDR 0x40
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(PCA9685_I2C_NODE);
    I2CConnectionZephyr connection(dev, PCA9685_ADDR);

    Pca9685Minimal pwm(connection);                   // Create PCA9685 driver, (connection)

    for (uint8_t step = 0; step <= 10; step++) {
        pwm.set_duty(0, step / 10.0f);                    // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → bool
        k_msleep(200);
    }
    pwm.set_duty(0, 0.0f);

    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
