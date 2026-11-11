#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "CAT5171.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, CAT5171Minimal::I2C_ADDRESS);
    CAT5171Minimal pot(connection);                     // Create CAT5171 driver, (connection, rAb=100000.0 Ω, rWiper=50.0 Ω)
    static const int codes[] = {0, 64, 128, 192, 255};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(codes[i]);                         // Set wiper position, (code 0–255) → void
        uint8_t w = pot.getWiper();                     // Read wiper position, () → uint8_t 0–255
        float r = pot.getResistance();                  // Read W–B resistance, () → float Ω
        printk("code %d read %d -> %.1f ohm\n", codes[i], (int)w, (double)r);
        k_sleep(K_MSEC(500));
    }
    pot.setResistance(22000.0f);                        // Set W–B resistance, (ohms Ω) → void
    return 0;
}
