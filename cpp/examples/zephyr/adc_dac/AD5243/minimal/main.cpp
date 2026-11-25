#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "AD5243.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, AD5243Minimal::I2C_ADDRESS);
    AD5243Minimal pot(connection);                      // Create AD5243 driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω)
    static const int codes[] = {0, 64, 128, 192, 255};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(0, codes[i]);                      // Set wiper position, (channel 0–1, code 0–255) → bool
        uint8_t w = pot.getWiper(0);                    // Read wiper position, (channel 0–1) → uint8_t 0–255
        float r = pot.getResistance(0);                 // Read W–B resistance, (channel 0–1) → float Ω
        printk("code %d read %d -> %.1f ohm\n", codes[i], (int)w, (double)r);
        k_sleep(K_MSEC(500));
    }
    pot.setResistance(1, 2200.0f);                      // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    return 0;
}
