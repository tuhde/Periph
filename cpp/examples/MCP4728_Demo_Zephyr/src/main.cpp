#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "MCP4728.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MCP4728_ADDR 0x60

const float STEP = 1.0f / 16.0f;
const unsigned long DELAY_MS = 50;

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, MCP4728_ADDR);
    MCP4728Full dac(transport);

    while (1) {
        // Apply four-point calibration voltages to channels A–D
        float calibration[4] = {0.0f, 1.0f / 3.0f, 2.0f / 3.0f, 1.0f};
        dac.set_all(calibration);
        for (uint8_t ch = 0; ch < 4; ch++) {
            uint16_t code = (uint16_t)(calibration[ch] * 4095.0f + 0.5f);
            printk("ch=%u fraction=%.4f code=%u approx_v=%.3fV\n",
                   ch, (double)calibration[ch], code, (double)(code * 3.3f / 4096.0f));
        }
        k_sleep(K_MSEC(500));

        // Synchronous staircase from 0 to full scale on all four channels
        for (int n = 0; n <= 16; n++) {
            float f = n * STEP;
            float fractions[4] = {f, f, f, f};
            dac.set_all(fractions);
            uint16_t code = (uint16_t)(f * 4095.0f + 0.5f);
            printk("step=%d fraction=%.4f code=%u approx_v=%.3fV\n",
                   n, (double)f, code, (double)(code * 3.3f / 4096.0f));
            k_sleep(K_MSEC(DELAY_MS));
        }

        // Reset all channels to 0 V before next loop iteration
        float zero[4] = {0.0f, 0.0f, 0.0f, 0.0f};
        dac.set_all(zero);
    }
    return 0;
}
