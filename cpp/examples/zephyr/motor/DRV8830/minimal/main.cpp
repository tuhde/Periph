#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "DRV8830.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, DRV8830Minimal::I2C_ADDRESS);
    DRV8830Minimal motor(connection);                       // Create DRV8830 driver, (connection)

    while (1) {
        motor.drive(3.0f);                                  // Drive at regulated voltage, (voltage V, + = forward) → void
        k_sleep(K_MSEC(2000));
        motor.drive(-3.0f);                                 // Drive at regulated voltage, (voltage V, - = reverse) → void
        k_sleep(K_MSEC(2000));
    }
    return 0;
}
