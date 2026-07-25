#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "MCP4728.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MCP4728_ADDR 0x60

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, MCP4728_ADDR);
    MCP4728Minimal dac(transport);

    while (1) {
        dac.set_voltage(0, 0.5f);
        dac.set_raw(1, 2048);
        float fractions[4] = {0.0f, 0.25f, 0.5f, 1.0f};
        dac.set_all(fractions);
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
