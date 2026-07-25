#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "AS5600.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define AS5600_ADDR 0x36

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, AS5600_ADDR);
    AS5600Minimal as5600(transport);

    while (1) {
        float a = as5600.angle();          // Read absolute angle, () → float degrees
        uint16_t r = as5600.angle_raw();   // Read scaled angle count, () → int 0-4095
        printk("angle=%.2f°  raw=%d\n", (double)a, r);
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
