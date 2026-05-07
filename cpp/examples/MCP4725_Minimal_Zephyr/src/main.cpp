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
    MCP4725Minimal dac(transport);

    dac.set_voltage(0.5);

    while (1) {
        k_sleep(K_SECONDS(1));
    }
    return 0;
}