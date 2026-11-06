#include <stdio.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "AD5248.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define AD5248_ADDR 0x2C

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, AD5248_ADDR);
    AD5248Minimal pot(connection, 10000.0f, 160.0f);             // Create AD5248 driver, (connection, r_ab=10000 Ω, r_wiper=160 Ω)

    pot.set_wiper(0, 64);                                    // Set channel 0 wiper, (channel 0-1, code 0-255) → void
    pot.set_resistance(1, 2200.0f);                          // Set channel 1 W-B resistance, (channel 0-1, ohms) → void

    while (true) {
        uint8_t code = pot.get_wiper(0);                     // Read channel 0 wiper, (channel 0-1) → uint8_t 0-255
        float ohms = pot.get_resistance(0);                  // Read channel 0 W-B resistance, (channel 0-1) → float Ω
        printf("ch0 code=%d R_WB ohm=%.1f\n", (int)(code), (double)(ohms));
        k_msleep(1000);
    }
    return 0;
}
