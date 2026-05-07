#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "MCP4725.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MCP4725_ADDR 0x60

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, MCP4725_ADDR);
    MCP4725Full dac(transport);

    float step = 1.0f / 20.0f;

    while (1) {
        for (int i = 0; i <= 20; i++) {
            float fraction = i * step;
            dac.set_voltage(fraction);
            printk("%.2f -> %.3f V\n", (double)fraction, (double)(fraction * 3.3f));
            k_sleep(K_MSEC(100));
        }
        for (int i = 20; i >= 0; i--) {
            float fraction = i * step;
            dac.set_voltage(fraction);
            printk("%.2f -> %.3f V\n", (double)fraction, (double)(fraction * 3.3f));
            k_sleep(K_MSEC(100));
        }
    }
    return 0;
}