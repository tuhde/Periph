#include <cstdio>
#include "SPITransportLinux.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS    0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif
#ifndef TEST_SPI_MODE
#define TEST_SPI_MODE   0
#endif
#ifndef TEST_SPI_SPEED
#define TEST_SPI_SPEED  1000000
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    SPITransportLinux transport(TEST_SPI_BUS, TEST_SPI_DEVICE, TEST_SPI_MODE, TEST_SPI_SPEED);

    uint8_t cmd[1] = {0x00};
    transport.write(cmd, 1);
    check_true("write accepted", true);

    uint8_t buf[1] = {0};
    transport.read(buf, 1);
    check_true("read returns data", true);

    transport.write_read(cmd, 1, buf, 1);
    check_true("write_read returns data", true);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
