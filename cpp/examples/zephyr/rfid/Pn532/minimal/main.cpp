#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Pn532.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define PN532_ADDR 0x24

static void print_hex(const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) printk("%02X", data[i]);
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr connection(i2c_dev, PN532_ADDR);
    PN532Minimal reader(connection);                               // Create PN532 driver, (connection) → void
    reader.init();                                                 // Wake, check firmware, SAM normal mode, finite retries, () → bool
    while (true) {
        uint8_t uid[10];
        uint8_t len = reader.read_uid(uid);                        // Detect a type A card, (uid*, timeoutMs=1000) → uint8_t length
        printk("UID ");
        if (len) print_hex(uid, len); else printk("none");
        printk("\n");
        k_msleep(1000);
    }
    return 0;
}
