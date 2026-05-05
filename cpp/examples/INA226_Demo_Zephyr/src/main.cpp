#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "INA226.h"

#define I2C_NODE   DT_NODELABEL(i2c0)
#define INA226_ADDR 0x40

// Alert threshold: warn when load exceeds 1.5 A
#define CURRENT_ALERT_A 1.5f

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, INA226_ADDR);
    INA226Full ina(transport);

    // Average 16 samples to smooth switching-regulator ripple
    ina.configure(4, 4, 4, 7);

    // Alert when current exceeds threshold so the host can log or react
    ina.set_alert(INA226Full::SOL, CURRENT_ALERT_A, false, true);

    while (1) {
        float v = ina.voltage();
        float i = ina.current();
        float p = ina.power();

        printk("%.3f V  %.3f A  %.3f W", (double)v, (double)i, (double)p);

        // Clear latched alert and report if threshold was crossed
        uint16_t flags = ina.alert_flags();
        if (flags & INA226Full::SOL) {
            printk("  OVERCURRENT");
        }
        printk("\n");

        k_sleep(K_MSEC(500));
    }
    return 0;
}
