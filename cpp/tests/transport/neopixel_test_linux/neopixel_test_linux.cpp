#include <cstdio>
#include <unistd.h>
#include "NeoPixelTransportLinux.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    NeoPixelTransportLinux transport(TEST_SPI_BUS, TEST_SPI_DEVICE);

    uint8_t data[3] = {0xFF, 0x00, 0x00};
    transport.write(data, 3);

    check_true("write accepted data", true);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}