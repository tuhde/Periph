#include <stdio.h>
#include <string.h>
#include "I2CConnectionMock.h"
#include "24AA02UID.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

// Access protected constants for building expected values.
class EEPROMTestAccess : public EEPROM24AA02UIDFull {
public:
    using EEPROM24AA02UIDFull::EEPROM24AA02UIDFull;
    using EEPROM24AA02UIDFull::ADDR_UID_BASE;
    using EEPROM24AA02UIDFull::ADDR_MFR_CODE;
    using EEPROM24AA02UIDFull::ADDR_DEV_CODE;
};

int main() {
    I2CConnectionMock connection;
    // UID (0xFC-0xFF), MSB first.
    connection.setRegister(EEPROMTestAccess::ADDR_UID_BASE, {0xAA, 0xBB, 0xCC, 0xDD});

    EEPROMTestAccess eeprom(connection);
    check_true(true, "init");

    uint8_t uid[4] = {0};
    eeprom.read_uid(uid);
    check_true(memcmp(uid, (const uint8_t[]){0xAA, 0xBB, 0xCC, 0xDD}, 4) == 0, "read_uid");

    connection.setRegister(0x10, {0x42});
    check_true(eeprom.read_byte(0x10) == 0x42, "read_byte");

    eeprom.write_byte(0x10, 0x99);
    check_true(connection.registers().at(0x10) == 0x99, "write_byte");
    // write_byte() delays instead of ACK-polling in C++ (no ACK/NACK
    // propagated by the Connection interface); it issues exactly one write.
    check_true(connection.writes().back().size() == 2 &&
               connection.writes().back()[0] == 0x10 && connection.writes().back()[1] == 0x99,
               "write_byte_issues_write");

    // Sequential read (0x05-0x08).
    connection.setRegister(0x05, {1, 2, 3, 4});
    uint8_t buf4[4] = {0};
    eeprom.read(0x05, buf4, 4);
    check_true(memcmp(buf4, (const uint8_t[]){1, 2, 3, 4}, 4) == 0, "read");

    uint8_t page[3] = {10, 20, 30};
    eeprom.write_page(0x08, page, 3);
    check_true(connection.registers().at(0x08) == 10 &&
               connection.registers().at(0x09) == 20 &&
               connection.registers().at(0x0A) == 30, "write_page");

    // write() spanning a page boundary: page 0 is 0x00-0x07, page 1 is
    // 0x08-0x0F. Starting at 0x05 with 10 bytes -> [0x05,0x06,0x07] (3
    // bytes, page 0) then [0x08..0x0E] (7 bytes, page 1).
    uint8_t data10[10];
    for (int i = 0; i < 10; i++) data10[i] = (uint8_t)(100 + i);
    eeprom.write(0x05, data10, 10);
    // C++'s write_page() issues exactly one write per call (no ack-poll
    // write_read), so the two page-chunk writes are the last two writes.
    const auto& writes = connection.writes();
    const auto& page1Chunk = writes[writes.size() - 1];
    const auto& page0Chunk = writes[writes.size() - 2];
    check_true(page0Chunk.size() == 4 && page0Chunk[0] == 0x05 &&
               page0Chunk[1] == 100 && page0Chunk[2] == 101 && page0Chunk[3] == 102,
               "write_page0_chunk");
    check_true(page1Chunk.size() == 8 && page1Chunk[0] == 0x08 &&
               page1Chunk[1] == 103 && page1Chunk[7] == 109,
               "write_page1_chunk");
    check_true(connection.registers().at(0x05) == 100 && connection.registers().at(0x06) == 101 &&
               connection.registers().at(0x07) == 102 && connection.registers().at(0x08) == 103 &&
               connection.registers().at(0x0E) == 109, "write_updated_registers");

    connection.setRegister(EEPROMTestAccess::ADDR_MFR_CODE, {0x29});
    check_true(eeprom.read_manufacturer_code() == 0x29, "read_manufacturer_code");

    connection.setRegister(EEPROMTestAccess::ADDR_DEV_CODE, {0x41});
    check_true(eeprom.read_device_code() == 0x41, "read_device_code");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
