#include <cstdio>
#include <unistd.h>
#include "I2CTransportLinux.h"
#include "24AA02UID.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x50
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: got 0x%02X, expected 0x%02X\n", label, got, expected); failed++; }
}

static void check_eq_bytes(const char* label, const uint8_t* got, const uint8_t* expected, uint8_t len) {
    for (uint8_t i = 0; i < len; i++) {
        if (got[i] != expected[i]) {
            printf("FAIL %s: byte %u got 0x%02X, expected 0x%02X\n", label, i, got[i], expected[i]);
            failed++;
            return;
        }
    }
    printf("PASS %s\n", label);
    passed++;
}

int main() {
    I2CTransportLinux transport(TEST_I2C_BUS, TEST_ADDR);
    EEPROM24AA02UIDFull eeprom(transport);

    uint8_t uid[4];
    eeprom.read_uid(uid);
    check_true("read_uid length 4", true);
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

    const uint8_t RANGE_ADDR = 0x50;
    const uint8_t RANGE_LEN  = 16;
    uint8_t range_read[16];
    eeprom.read(RANGE_ADDR, range_read, RANGE_LEN);
    check_true("sequential read length", true);

    uint8_t uid2[4];
    eeprom.read_uid(uid2);
    check_eq_bytes("uid unchanged after writes", uid2, uid, 4);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
