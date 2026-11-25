#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "AXP2101.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define AXP2101_ADDR 0x34

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, AXP2101_ADDR);
    AXP2101Minimal chip(connection);                    // Create AXP2101 driver, (connection)

    while (true) {
        float v = chip.batteryVoltage();                // Read battery voltage, () → float V
        uint8_t pct = chip.batteryPercent();            // Read state of charge, () → uint8_t %
        uint8_t state = chip.chargeState();             // Read charge state, () → uint8_t (0 trickle … 5 none)
        bool vbus = chip.vbusPresent();                 // Check input source, () → bool
        printk("%.3f V  %d %%  state=%d  vbus=%s\n", (double)v, (int)pct, (int)state, (vbus) ? "true" : "false");
        k_sleep(K_MSEC(1000));
    }
    return 0;
}
