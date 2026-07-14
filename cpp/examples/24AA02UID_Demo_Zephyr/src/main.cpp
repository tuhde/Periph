#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "24AA02UID.h"

#define I2C_NODE       DT_NODELABEL(i2c0)
#define EEPROM_ADDR    0x50

static void print_hex_byte(uint8_t b) {
    if (b < 0x10) printk("0");
    printk("%X", b);
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, EEPROM_ADDR);
    EEPROM24AA02UIDFull eeprom(transport);                     // Create 24AA02UID driver, (transport, addr=0x50) → void

    // --- Read the chip's factory-programmed 32-bit serial number ---
    // The UID at 0xFC-0xFF never changes and identifies the device
    // across the entire 256-byte address space.
    uint8_t uid[4];
    eeprom.read_uid(uid);                                       // Read 32-bit unique serial number, (buf[4]) → void
                                                                // reads 4 bytes at 0xFC-0xFF
    printk("Device UID: ");
    for (uint8_t i = 0; i < 4; i++) print_hex_byte(uid[i]);
    printk("\n");
    uint32_t uid_int = ((uint32_t)uid[0] << 24) | ((uint32_t)uid[1] << 16)
                     | ((uint32_t)uid[2] << 8)  |  (uint32_t)uid[3];
    printk("Device UID int: %u\n", uid_int);

    // --- Maintain a 4-byte boot counter in user EEPROM at 0x00-0x03 ---
    // Read the existing value (or zero on a fresh chip), increment,
    // write back as 4 big-endian bytes. The user EEPROM is rewritable;
    // the UID region above 0x80 is not, so the two stay independent.
    uint8_t existing[4];
    eeprom.read(0x00, existing, 4);                             // Sequential read, (address, buf, length) → void
                                                                // reads 4 bytes from user EEPROM
    uint32_t counter = ((uint32_t)existing[0] << 24)
                     | ((uint32_t)existing[1] << 16)
                     | ((uint32_t)existing[2] << 8)
                     |  (uint32_t)existing[3];
    counter++;
    uint8_t updated[4] = {
        (uint8_t)(counter >> 24), (uint8_t)(counter >> 16),
        (uint8_t)(counter >> 8),  (uint8_t)(counter)
    };
    eeprom.write(0x00, updated, 4);                             // Arbitrary-length write, (address, data, length) → void
                                                                // writes 4 bytes
    printk("Boot count: %u\n", counter);

    for (int n = 0; n < 5; n++) {
        // --- Loop reading the UID only, showing it never changes ---
        // The two distinct areas of the chip (immutable identification
        // above 0x80, rewritable storage below 0x80) are exercised
        // independently.
        eeprom.read_uid(uid);                                   // Read 32-bit unique serial number, (buf[4]) → void
        printk("[%d] UID: ", n);
        for (uint8_t i = 0; i < 4; i++) print_hex_byte(uid[i]);
        printk("  (counter in user EEPROM 0x00-0x03)\n");
        k_sleep(K_SECONDS(2));
    }
    return 0;
}
