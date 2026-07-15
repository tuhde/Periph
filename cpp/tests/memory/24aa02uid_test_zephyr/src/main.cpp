#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "24AA02UID.h"

#ifndef EEPROM_I2C_NODE
#define EEPROM_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef EEPROM_ADDR
#define EEPROM_ADDR 0x50
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

static void check_eq(const char *label, uint8_t got, uint8_t expected) {
    if (got == expected) { printk("PASS %s\n", label); passed++; }
    else { printk("FAIL %s: got 0x%02X, expected 0x%02X\n", label, got, expected); failed++; }
}

static void check_eq_bytes(const char *label, const uint8_t* got, const uint8_t* expected, uint8_t len) {
    for (uint8_t i = 0; i < len; i++) {
        if (got[i] != expected[i]) {
            printk("FAIL %s: byte %u got 0x%02X, expected 0x%02X\n", label, i, got[i], expected[i]);
            failed++;
            return;
        }
    }
    printk("PASS %s\n", label);
    passed++;
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(EEPROM_I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, EEPROM_ADDR);
    EEPROM24AA02UIDFull eeprom(transport);

    uint8_t uid[4];
    eeprom.read_uid(uid);
    check_true(true, "read_uid length 4");
    check_eq("read_manufacturer_code", eeprom.read_manufacturer_code(), 0x29);
    check_eq("read_device_code",       eeprom.read_device_code(),       0x41);

    const uint8_t TEST_ADDRESS = 0x10;
    const uint8_t TEST_VALUE   = 0x5A;
    eeprom.write_byte(TEST_ADDRESS, TEST_VALUE);
    check_eq("write_byte/read_byte round-trip", eeprom.read_byte(TEST_ADDRESS), TEST_VALUE);

    const uint8_t  PAGE_ADDR  = 0x40;
    const uint8_t  PAGE_DATA[] = { 0x11, 0x22, 0x33, 0x44 };
    eeprom.write_page(PAGE_ADDR, PAGE_DATA, 4);
    uint8_t page_read[4];
    eeprom.read(PAGE_ADDR, page_read, 4);
    check_eq_bytes("write_page read-back", page_read, PAGE_DATA, 4);

    const uint8_t  CROSS_ADDR  = 0x06;
    const uint8_t  CROSS_DATA[] = { 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF };
    eeprom.write(CROSS_ADDR, CROSS_DATA, 6);
    uint8_t cross_read[6];
    eeprom.read(CROSS_ADDR, cross_read, 6);
    check_eq_bytes("cross-page write read-back", cross_read, CROSS_DATA, 6);

    uint8_t uid2[4];
    eeprom.read_uid(uid2);
    check_eq_bytes("uid unchanged after writes", uid2, uid, 4);

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
