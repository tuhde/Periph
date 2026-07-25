#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "Rda5807m.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define RDA5807M_ADDR 0x10

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, RDA5807M_ADDR);
    RDA5807MMinimal fm(transport, 100.0f, 8);

    while (1) {
        float freq;
        if (fm.seek(true, freq)) {
            printk("Station: %d.%02d MHz\n", (int)freq, (int)((freq - (int)freq) * 100));
        }
        k_sleep(K_SECONDS(3));
    }
    return 0;
}
