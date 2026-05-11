#include <cstdio>
#include <unistd.h>
#include "SPITransportLinux.h"

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
    SPITransportLinux transport(TEST_SPI_BUS, TEST_SPI_DEVICE);

    uint8_t tx_data[] = {0x01, 0x02, 0x03};
    uint8_t rx_buf[3] = {0};

    transport.write(tx_data, sizeof(tx_data));
    check_true("write completed", true);

    transport.read(rx_buf, sizeof(rx_buf));
    check_true("read completed", true);

    uint8_t cmd[] = {0x55, 0xAA};
    uint8_t resp[2] = {0};
    transport.write_read(cmd, sizeof(cmd), resp, sizeof(resp));
    check_true("write_read completed", true);

    transport.close();

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}