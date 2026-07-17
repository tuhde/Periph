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
    RDA5807MFull fm(transport, 100.0f, 8);

    fm.set_frequency(97.5f);
    printk("Frequency: %d Hz/100\n", (int)(fm.frequency() * 100));

    fm.set_volume(10);
    fm.mute(false);

    float freq;
    if (fm.seek(true, freq)) {
        printk("Seek found: %d Hz/100\n", (int)(freq * 100));
    }

    fm.configure(RDA5807MFull::BAND_WORLD, RDA5807MFull::SPACE_100K, 1, 8, 1);
    fm.set_bass_boost(true);
    fm.set_mono(false);
    fm.set_softmute(true);

    fm.enable_rds(true);
    k_sleep(K_SECONDS(1));
    printk("RDS ready: %d\n", fm.rds_ready());
    uint16_t a, b, c, d;
    if (fm.read_rds_group(a, b, c, d)) {
        printk("RDS blocks: %04X %04X %04X %04X\n", a, b, c, d);
    }

    printk("Stereo: %d\n", fm.is_stereo());
    printk("Station: %d\n", fm.is_station());
    printk("Ready: %d\n", fm.is_ready());
    printk("RSSI: %d\n", fm.signal_strength());

    fm.standby(true);
    k_sleep(K_MSEC(10));
    fm.standby(false);

    fm.soft_reset();

    while (1) {
        k_sleep(K_SECONDS(1));
    }
    return 0;
}
