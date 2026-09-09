#include <cstdio>
#include "SPIConnectionLinux.h"
#include "RFM9x.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEV
#define TEST_SPI_DEV 0
#endif
#ifndef TEST_FREQ_HZ
#define TEST_FREQ_HZ 868000000UL
#endif

static int passed = 0;
static int failed = 0;

static void check_eq(const char* label, uint8_t got, uint8_t expected) {
    if (got == expected) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s: got 0x%02X, expected 0x%02X\n", label, got, expected); failed++; }
}

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    SPIConnectionLinux transport(TEST_SPI_BUS, TEST_SPI_DEV, 0, 5000000);
    RFM95Full radio(transport, TEST_FREQ_HZ);

    check_eq("version", radio.version(), 0x12);

    radio.configure(7, 125.0, 5);
    check_true("configure", true);

    radio.standby();
    check_eq("standby_mode", radio._read_reg(0x01) & 0x07, 0x01);

    radio.send((const uint8_t*)"test", 4);
    check_eq("irq_tx_done_cleared", radio._read_reg(0x12) & 0x08, 0x00);

    radio.sleep();
    check_eq("sleep_mode", radio._read_reg(0x01) & 0x07, 0x00);

    radio.standby();
    check_true("wake", true);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
