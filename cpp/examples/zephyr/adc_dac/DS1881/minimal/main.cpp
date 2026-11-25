#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "DS1881.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, DS1881Minimal::I2C_ADDRESS);
    DS1881Minimal amp(connection);                      // Create DS1881 driver, (connection)
    amp.init();                                         // Apply defaults, () → void
    static const int dbs[] = {0, 10, 20, 40};
    for (int i = 0; i < 4; i++) {
        amp.setAttenuation(0, (float)dbs[i]);           // Set attenuation, (channel 0–1, db dB) → void
        amp.setAttenuation(1, (float)dbs[i]);           // Set attenuation, (channel 0–1, db dB) → void
        float a = amp.getAttenuation(0);                // Read attenuation, (channel 0–1) → float dB
        printk("attenuation %d dB, read back %d dB\n", dbs[i], (int)a);
        k_sleep(K_MSEC(500));
    }
    amp.mute(0);                                        // Mute channel, (channel 0–1) → void
    return 0;
}
