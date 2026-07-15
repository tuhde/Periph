#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "24AA02UID.h"

#define I2C_NODE       DT_NODELABEL(i2c0)
#define EEPROM_ADDR    0x50

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, EEPROM_ADDR);
    EEPROM24AA02UIDMinimal eeprom(transport);                  // Create 24AA02UID driver, (transport, addr=0x50) → void

    while (1) {
        uint8_t uid[4];
        eeprom.read_uid(uid);                                   // Read 32-bit unique serial number, (buf[4]) → void
        printk("UID: %02X%02X%02X%02X\n", uid[0], uid[1], uid[2], uid[3]);
        k_sleep(K_SECONDS(2));
    }
    return 0;
}
