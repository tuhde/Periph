#include <cstdio>
#include <cstring>
#include "UARTTransportLinux.h"

// Assumes a loopback jumper bridging TXD and RXD on the UART port under test.
#ifndef TEST_UART_PORT
#define TEST_UART_PORT "/dev/ttyS0"
#endif
#ifndef TEST_UART_BAUD
#define TEST_UART_BAUD 9600
#endif

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\n", label); passed++; }
    else           { printf("FAIL %s\n", label); failed++; }
}

int main() {
    UARTTransportLinux transport(TEST_UART_PORT, TEST_UART_BAUD);

    const uint8_t payload[1] = {0x42};
    transport.write(payload, 1);
    check_true("write accepted", true);

    uint8_t buf[1] = {0};
    transport.read(buf, 1);
    check_true("read returns 1 byte", true);
    check_true("loopback byte matches", buf[0] == 0x42);

    const uint8_t cmd[2] = {0xA5, 0x5A};
    uint8_t resp[2] = {0, 0};
    transport.write_read(cmd, 2, resp, 2);
    check_true("write_read loopback matches", resp[0] == 0xA5 && resp[1] == 0x5A);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
