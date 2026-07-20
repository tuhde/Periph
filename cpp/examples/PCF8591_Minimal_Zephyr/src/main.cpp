#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "PCF8591.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define PCF8591_ADDR 0x48

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, PCF8591_ADDR);
    PCF8591Minimal adc(transport);

    while (1) {
        uint8_t ch0 = adc.read_channel(0);                  // Read single channel, (channel=0–3) → uint8_t
        uint8_t raw[PCF8591Minimal::NUM_CHANNELS];
        adc.read_all(raw);                                  // Read all four channels, (out[4]) → None
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
