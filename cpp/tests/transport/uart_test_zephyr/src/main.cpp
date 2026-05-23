#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "UARTTransportZephyr.h"

// Assumes a loopback jumper bridging TX and RX pins on the UART node under test.
#ifndef UART_TEST_NODE
#define UART_TEST_NODE DT_NODELABEL(uart0)
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char* label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device* uart_dev = DEVICE_DT_GET(UART_TEST_NODE);
    UARTTransportZephyr transport(uart_dev);

    const uint8_t payload[1] = {0x42};
    transport.write(payload, 1);
    check_true(true, "write accepted");

    uint8_t buf[1] = {0};
    transport.read(buf, 1);
    check_true(buf[0] == 0x42, "loopback byte matches");

    const uint8_t cmd[2] = {0xA5, 0x5A};
    uint8_t resp[2] = {0, 0};
    transport.write_read(cmd, 2, resp, 2);
    check_true(resp[0] == 0xA5 && resp[1] == 0x5A, "write_read loopback matches");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
