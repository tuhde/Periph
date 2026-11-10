#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "PCF85063A.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, PCF85063AMinimal::I2C_ADDRESS);

    PCF85063AMinimal rtc(connection);                        // Create PCF85063A driver, (connection)

    for (int i = 0; i < 10; ++i) {
        PCF85063AMinimal::DateTime dt;
        rtc.getDatetime(dt);                                 // Read calendar clock, () → DateTime
        printk("%04u-%02u-%02u %02u:%02u:%02u\n",
            dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
