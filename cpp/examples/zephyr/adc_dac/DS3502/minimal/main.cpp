#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "DS3502.h"

#define I2C_NODE DT_NODELABEL(i2c0)

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, DS3502Minimal::I2C_ADDRESS);
    DS3502Minimal pot(connection, 15.0f, 0.0f);         // Create DS3502 driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    static const int codes[] = {0, 32, 64, 96, 127};
    for (int i = 0; i < 5; i++) {
        pot.setWiper(codes[i]);                         // Set wiper position, (code 0–127) → void
        uint8_t w = pot.getWiper();                     // Read wiper position, () → uint8_t 0–127
        float v = pot.getVoltage();                     // Read unloaded RW voltage, () → float V
        printk("code %d read %d -> %.3f V\n", codes[i], (int)w, (double)v);
        k_sleep(K_MSEC(500));
    }
    pot.setVoltage(6.0f);                               // Set RW output voltage, (volts V) → void
    return 0;
}
