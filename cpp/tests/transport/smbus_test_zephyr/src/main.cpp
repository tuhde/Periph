#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SMBusTransportZephyr.h"

#ifndef SMBUS_I2C_NODE
#define SMBUS_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x40
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(SMBUS_I2C_NODE);

    // --- address validation ---
    {
        SMBusTransportZephyr bad(i2c_dev, 0x07);
        check_true(!bad.valid(), "addr 0x07 rejected");
    }
    {
        SMBusTransportZephyr bad(i2c_dev, 0x78);
        check_true(!bad.valid(), "addr 0x78 rejected");
    }

    // --- basic I/O without PEC ---
    SMBusTransportZephyr transport(i2c_dev, TEST_ADDR);

    uint8_t buf[1] = {0};
    transport.read(buf, 1);
    check_true(transport.valid(), "read accepted");

    uint8_t reg[1] = {0x00};
    transport.write(reg, 1);
    check_true(transport.valid(), "write accepted");

    transport.write_read(reg, 1, buf, 1);
    check_true(transport.valid(), "write_read accepted");

    // --- write with PEC enabled ---
    SMBusTransportZephyr transport_pec(i2c_dev, TEST_ADDR, true);
    transport_pec.write(reg, 1);
    check_true(transport_pec.valid(), "write with PEC accepted");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
