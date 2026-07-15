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

    uint8_t uid[4];
    eeprom.read_uid(uid);                                       // Read 32-bit unique serial number, (buf[4]) → void
                                                                // reads 4 bytes at 0xFC-0xFF
    printk("UID bytes: ");
    for (uint8_t i = 0; i < 4; i++) print_hex_byte(uid[i]);
    printk("\n");

    uint32_t uid_int = ((uint32_t)uid[0] << 24) | ((uint32_t)uid[1] << 16)
                     | ((uint32_t)uid[2] << 8)  |  (uint32_t)uid[3];
    printk("UID int:   %u\n", uid_int);

    uint8_t mfr = eeprom.read_manufacturer_code();             // Read manufacturer code, () → byte
                                                                // reads 0xFA; expect 0x29 (Microchip)
    uint8_t dev = eeprom.read_device_code();                   // Read device code, () → byte
                                                                // reads 0xFB; expect 0x41
    printk("MFR: "); print_hex_byte(mfr);
    printk("  DEV: "); print_hex_byte(dev);
    printk("\n");

    uint8_t first = eeprom.read_byte(0x00);                    // Read a single byte, (address=0x00-0x7F) → byte
                                                                // random read at user EEPROM address
    printk("First byte: "); print_hex_byte(first); printk("\n");

    eeprom.write_byte(0x10, 0xA5);                             // Write a single byte, (address, value) → void
                                                                // byte write + delay until complete (max 5 ms)
    uint8_t verify = eeprom.read_byte(0x10);                   // Read a single byte, (address=0x00-0x7F) → byte
    printk("Wrote 0xA5, read back: "); print_hex_byte(verify); printk("\n");

    uint8_t buf[8];
    eeprom.read(0x20, buf, 8);                                 // Sequential read, (address, buf, length) → void
                                                                // reads 8 bytes starting at address
    printk("Block @ 0x20: ");
    for (uint8_t i = 0; i < 8; i++) { print_hex_byte(buf[i]); printk(" "); }
    printk("\n");

    uint8_t page_data[] = { 0x01, 0x02, 0x03, 0x04 };
    eeprom.write_page(0x40, page_data, 4);                     // Page write, (address, data, length) → void
                                                                // writes up to 8 bytes within one page

    uint8_t cross[] = { 0xAA, 0xBB, 0xCC, 0xDD, 0xEE };
    eeprom.write(0x44, cross, 5);                              // Arbitrary-length write, (address, data, length) → void
                                                                // splits at 8-byte page boundaries; waits for each chunk
    printk("Multi-page write complete\n");

    return 0;
}
