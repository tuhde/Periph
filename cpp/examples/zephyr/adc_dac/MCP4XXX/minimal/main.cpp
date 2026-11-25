#include <stdio.h>
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "MCP4XXX.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MCP4XXX_ADDR 0x58

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, MCP4XXX_ADDR);
    MCP4XXXMinimal pot(connection, MCP4XXXPart::MCP4532, 10000.0f, 75.0f); // Create MCP4XXX driver, (connection, part, r_ab=10000 Ω, r_wiper=75 Ω)
    pot.init();                                              // Bus check, () → void

    pot.set_wiper(32, 0);                                    // Set wiper, (code 0-128, channel=0) → void
    pot.set_resistance(2200.0f, 0);                          // Set W-B resistance, (ohms, channel=0) → void

    while (true) {
        uint16_t code = pot.get_wiper(0);                        // Read wiper, (channel=0) → uint16_t 0-128
        float ohms = pot.get_resistance(0);                      // Read W-B resistance, (channel=0) → float Ω
        printf("code=%d R_WB ohm=%.1f\n", (int)(code), (double)(ohms));
        k_msleep(1000);
    }
    return 0;
}
