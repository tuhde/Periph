#include <cstdio>
#include <stdexcept>
#include "SMBusConnectionLinux.h"

#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x40
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    // --- address validation ---
    try {
        SMBusConnectionLinux bad(TEST_I2C_BUS, 0x07);
        check_true("addr 0x07 rejected", false);
    } catch (const std::runtime_error&) {
        check_true("addr 0x07 rejected", true);
    }

    try {
        SMBusConnectionLinux bad(TEST_I2C_BUS, 0x78);
        check_true("addr 0x78 rejected", false);
    } catch (const std::runtime_error&) {
        check_true("addr 0x78 rejected", true);
    }

    // --- basic I/O without PEC ---
    SMBusConnectionLinux connection(TEST_I2C_BUS, TEST_ADDR);

    uint8_t buf[1] = {0};
    connection.read(buf, 1);
    check_true("read accepted", connection.valid());

    uint8_t reg[1] = {0x00};
    connection.write(reg, 1);
    check_true("write accepted", connection.valid());

    connection.write_read(reg, 1, buf, 1);
    check_true("write_read accepted", connection.valid());

    // --- write with PEC enabled ---
    SMBusConnectionLinux transport_pec(TEST_I2C_BUS, TEST_ADDR, true);
    transport_pec.write(reg, 1);
    check_true("write with PEC accepted", transport_pec.valid());

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
