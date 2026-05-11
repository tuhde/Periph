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
    INA3221Full ina(transport);                           // Create INA3221 driver, (transport, r_shunt=0.1 Ω)

    printk("V1       I1       P1       V2       I2       P2       V3       I3       P3\n");
    for (int t = 0; t < 30; t++) {
        for (uint8_t ch = 1; ch <= 3; ch++) {
            float v = ina.voltage(ch);                   // Read bus voltage, (channel) → float V
            float i = ina.current(ch);                    // Read load current, (channel) → float A
            float p = ina.power(ch);                      // Read power, (channel) → float W
            printk("%-8.3f %-8.4f %-8.4f ", (double)v, (double)i, (double)p);
        }
        printk("\n");

        if (t == 9) {
            for (uint8_t ch = 1; ch <= 3; ch++) {
                float current_ma = ina.current(ch) * 1000.0f;
                ina.set_critical_alert(ch, current_ma * 1.5f / 1000.0f);
            }
            printk("alerts armed\n");
        }

        if (t == 19) {
            uint8_t channels[] = {1, 2, 3};
            ina.set_summation_channels(channels, 3, 0.3f);   // Set summation channels, (channels, n, limit_v) → None
            printk("summation armed\n");
        }

        k_sleep(K_SECONDS(1));
    }

    uint16_t flags = ina.alert_flags();                  // Read alert flags, () → int
    printk("Mask/Enable: 0x%04X\n", flags);

    printk("===DONE===\n");
    return 0;
}