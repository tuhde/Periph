#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "DS3231.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, DS3231Minimal::I2C_ADDRESS);
    DS3231Minimal rtc(connection);                          // Create DS3231 driver, (connection)

    while (1) {
        DS3231Minimal::DateTime dt;
        rtc.getDatetime(dt);                                // Read calendar clock, () → DateTime
        float tempC = rtc.readTemperature();                // Read on-chip temperature, () → float C
        printk("%04u-%02u-%02u %02u:%02u:%02u  %.2f C\n",
               dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second, (double)tempC);
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
