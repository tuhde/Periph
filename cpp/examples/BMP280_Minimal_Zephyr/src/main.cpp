#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "BMP280.h"

#ifndef BMP280_I2C_NODE
#define BMP280_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP280_ADDR
#define BMP280_ADDR 0x76
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP280_I2C_NODE);
    I2CTransportZephyr transport(dev, BMP280_ADDR);
    BMP280Minimal bmp(transport);                      // Create BMP280 driver, (transport, addr=0x76)

    for (int i = 0; i < 5; i++) {
        float t = bmp.temperature();                   // Read temperature, () → float C
        float p = bmp.pressure();                      // Read pressure, () → float hPa
        printk("%.1f C, %.1f hPa\n", t, p);
        k_sleep(K_SECONDS(1));
    }
    return 0;
}