#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "INA3221.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define INA3221_ADDR 0x40

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, INA3221_ADDR);
    INA3221Minimal ina(transport);                        // Create INA3221 driver, (transport, r_shunt=0.1 Ω)

    while (1) {
        for (uint8_t ch = 1; ch <= 3; ch++) {
            printk("ch%d: %.3fV %.4fA %.4fW ", ch,     // Read bus voltage, (channel) → V
                                                (double)ina.voltage(ch),
                                                (double)ina.current(ch),   // Read load current, (channel) → A
                                                (double)ina.power(ch));    // Read power, (channel) → W
        }
        printk("\n");
        k_sleep(K_SECONDS(1));
    }
    return 0;
}