#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "AHT21.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define AHT21_ADDR 0x38

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, AHT21_ADDR);
    AHT21Minimal aht(transport);                                       // Create AHT21 driver, (transport, addr=0x38) → void

    while (1) {
        float t, h;
        aht.read(t, h);                                                // Trigger measurement, (temperature_c, humidity_pct) → void
        printk("Temperature: %.2f C\n", (double)t);
        printk("Humidity:    %.2f %%RH\n", (double)h);
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
