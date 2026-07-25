#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "MCP4725.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MCP4725_ADDR 0x60

const float STEP = 1.0f / 20.0f;
const unsigned long DELAY_MS = 100;

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, MCP4725_ADDR);
    MCP4725Full dac(transport);

    while (1) {
        for (int n = 0; n <= 20; n++) {
            float fraction = n * STEP;
            dac.set_voltage(fraction);
            uint16_t code = (uint16_t)(fraction * 4095.0f + 0.5f);
            float approx_v = code * 3.3f / 4096.0f;
            printk("n=%d fraction=%.2f code=%u approx_v=%.3fV\n", n, fraction, code, (double)approx_v);
            k_sleep(K_MSEC(DELAY_MS));
        }
        for (int n = 20; n >= 0; n--) {
            float fraction = n * STEP;
            dac.set_voltage(fraction);
            uint16_t code = (uint16_t)(fraction * 4095.0f + 0.5f);
            float approx_v = code * 3.3f / 4096.0f;
            printk("n=%d fraction=%.2f code=%u approx_v=%.3fV\n", n, fraction, code, (double)approx_v);
            k_sleep(K_MSEC(DELAY_MS));
        }
    }
    return 0;
}