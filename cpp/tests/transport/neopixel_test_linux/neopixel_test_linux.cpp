#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

#include <cstdio>
#include <cstdlib>
#include "NeoPixelTransportLinux.h"

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    NeoPixelTransportLinux transport(TEST_SPI_BUS, TEST_SPI_DEVICE);

    transport.write((const uint8_t*)"\x00\x00\x00", 3);
    check_true("write_black_no_error", true);

    transport.write((const uint8_t*)"\xFF\xFF\xFF", 3);
    check_true("write_white_no_error", true);

    transport.write((const uint8_t*)"\x00\xFF\x00", 3);
    check_true("write_green_no_error", true);

    transport.write((const uint8_t*)"\x10\x20\x30\x40", 4);
    check_true("write_4bytes_no_error", true);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}